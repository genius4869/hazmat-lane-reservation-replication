import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.*;

/** Independent, fixed-route risk evaluation. All values include nominal risk.
 * The supplied routes contain physical nodes, including both endpoints; a
 * one-node route represents disposal at the source. No infrastructure is changed.
 * Bounds have MAXIMIZATION orientation: a feasible scenario gives a lower bound.
 */
public final class UnifiedRiskEvaluator {
    private UnifiedRiskEvaluator() { }

    public static final class Result {
        public double value = Double.NaN;
        public double lowerBound = Double.NaN;
        public double upperBound = Double.NaN;
        public double nominalRisk = Double.NaN;
        public double seconds;
        public boolean feasible;
        public boolean optimal;
        public String status = "NOT_STARTED";
        public String message = "";
    }

    private static final class Term {
        final int task, arc;
        final double n, k, r, q;
        Term(int s, int a, double n, double k, double r, double q) {
            task=s; arc=a; this.n=n; this.k=k; this.r=r; this.q=q;
        }
    }

    private static final class Input {
        final int tasks, arcs;
        final double gammaU, gammaV, nominal, boxUpper;
        final ArrayList<Term> terms;
        Input(int s, int a, double gu, double gv, ArrayList<Term> terms) {
            tasks=s; arcs=a; gammaU=Math.min(gu,s); gammaV=Math.min(gv,a);
            this.terms=terms;
            double nominal=0, upper=0;
            for(Term t:terms) {
                nominal+=t.n*t.r;
                upper+=(t.n+t.k)*(t.r+t.q);
            }
            finiteNonnegative(nominal,"nominal risk");
            finiteNonnegative(upper,"box risk upper bound");
            this.nominal=nominal; boxUpper=upper;
        }
    }

    public static Result evaluateConservative(DataCreator data, List<int[]> routes, double seconds) {
        return evaluate(data,routes,seconds,false);
    }

    /** Exact global MILP over demand-polytope vertices and continuous arc deviations.
     * Nonnegative coefficients permit using the entire capped demand budget.
     * Maximizing over v first gives a convex function of u; hence a demand vertex
     * attains the global maximum. Binary-times-continuous products are exact.
     */
    public static Result evaluateOriginal(DataCreator data, List<int[]> routes, double seconds) {
        return evaluate(data,routes,seconds,true);
    }

    private static Result evaluate(DataCreator data,List<int[]> routes,double limit,boolean original) {
        long started=System.nanoTime();
        if(!Double.isFinite(limit))throw new IllegalArgumentException("Nonfinite evaluation time limit");
        Input in=readInput(data,routes);
        Result result=new Result();
        result.nominalRisk=in.nominal;
        // u=v=0 is an explicit feasible scenario, even if no solver time remains.
        result.feasible=true; result.value=in.nominal; result.lowerBound=in.nominal;
        result.upperBound=in.boxUpper;
        Double analytical=analyticalValue(in);
        if(analytical!=null) {
            result.value=analytical; result.lowerBound=analytical; result.upperBound=analytical;
            result.optimal=true; result.status="OPTIMAL_ANALYTICAL";
            result.seconds=elapsed(started); return result;
        }
        if(limit<=0) {
            result.status="NO_TIME"; result.seconds=elapsed(started); return result;
        }
        IloCplex cp=null;
        try {
            cp=new IloCplex(); cp.setOut(null);
            cp.setParam(IloCplex.Param.Threads,1);
            cp.setParam(IloCplex.Param.RandomSeed,0);
            cp.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility,1e-9);
            cp.setParam(IloCplex.Param.Simplex.Tolerances.Optimality,1e-9);
            cp.setParam(IloCplex.Param.MIP.Tolerances.Integrality,1e-9);
            cp.setParam(IloCplex.Param.MIP.Tolerances.MIPGap,0.0);
            cp.setParam(IloCplex.Param.MIP.Tolerances.AbsMIPGap,1e-8);
            IloNumVar[] v=cp.numVarArray(in.arcs,0.0,1.0);
            IloLinearNumExpr vb=cp.linearNumExpr();
            for(IloNumVar a:v)vb.addTerm(1.0,a);
            cp.addLe(vb,in.gammaV,"arc_budget");
            IloNumVar[] u=null;
            IloIntVar[] b=null,d=null;
            double fraction=0;
            if(original) {
                int count=(int)Math.floor(in.gammaU);
                fraction=in.gammaU-count;
                b=cp.boolVarArray(in.tasks);
                IloLinearNumExpr full=cp.linearNumExpr();
                for(IloIntVar a:b)full.addTerm(1.0,a);
                cp.addEq(full,count,"demand_full_vertices");
                if(fraction>0) {
                    d=cp.boolVarArray(in.tasks);
                    IloLinearNumExpr fractional=cp.linearNumExpr();
                    for(int s=0;s<in.tasks;s++) {
                        fractional.addTerm(1.0,d[s]);
                        cp.addLe(cp.sum(b[s],d[s]),1.0,"demand_vertex_disjoint_"+s);
                    }
                    cp.addEq(fractional,1.0,"demand_fractional_vertex");
                }
            } else {
                u=cp.numVarArray(in.tasks,0.0,1.0);
                IloLinearNumExpr ub=cp.linearNumExpr();
                for(IloNumVar a:u)ub.addTerm(1.0,a);
                cp.addLe(ub,in.gammaU,"demand_budget");
            }
            IloLinearNumExpr objective=cp.linearNumExpr();
            for(Term t:in.terms) {
                if(t.n*t.q!=0)objective.addTerm(t.n*t.q,v[t.arc]);
                if(original) {
                    if(t.k*t.r!=0) {
                        objective.addTerm(t.k*t.r,b[t.task]);
                        if(d!=null)objective.addTerm(t.k*t.r*fraction,d[t.task]);
                    }
                    if(t.k*t.q!=0) {
                        IloNumVar bv=binaryProduct(cp,b[t.task],v[t.arc],"bv_"+t.task+"_"+t.arc);
                        objective.addTerm(t.k*t.q,bv);
                        if(d!=null) {
                            IloNumVar dv=binaryProduct(cp,d[t.task],v[t.arc],"dv_"+t.task+"_"+t.arc);
                            objective.addTerm(t.k*t.q*fraction,dv);
                        }
                    }
                } else {
                    if(t.k*t.r!=0)objective.addTerm(t.k*t.r,u[t.task]);
                    if(t.k*t.q!=0) {
                        IloNumVar w=cp.numVar(0.0,1.0,"w_"+t.task+"_"+t.arc);
                        cp.addLe(w,u[t.task]); cp.addLe(w,v[t.arc]);
                        objective.addTerm(t.k*t.q,w);
                    }
                }
            }
            cp.addMaximize(objective);
            double remaining=limit-elapsed(started);
            if(remaining<=0) {
                result.status="NO_TIME_AFTER_BUILD"; return result;
            }
            cp.setParam(IloCplex.Param.TimeLimit,remaining);
            boolean solved=cp.solve();
            result.status=cp.getStatus().toString()+"/"+cp.getCplexStatus().toString();
            if(solved) {
                double[] uv=new double[in.tasks], vv=cp.getValues(v);
                for(int s=0;s<in.tasks;s++)uv[s]=original
                        ?cp.getValue(b[s])+(d==null?0:fraction*cp.getValue(d[s])):cp.getValue(u[s]);
                // Project numerical tolerance excursions into the original budget sets.
                project(uv,in.gammaU); project(vv,in.gammaV);
                double feasible=scenario(in,uv,vv,original);
                result.value=Math.max(in.nominal,feasible);
                result.lowerBound=result.value;
                double bound=Double.NaN;
                if(original)bound=cp.getBestObjValue()+in.nominal;
                else if(cp.getStatus()==IloCplex.Status.Optimal)bound=cp.getObjValue()+in.nominal;
                if(Double.isFinite(bound))result.upperBound=Math.min(in.boxUpper,bound);
            } else if(original) {
                // A MIP may have a global upper bound even without its own incumbent.
                try {
                    double bound=cp.getBestObjValue()+in.nominal;
                    if(Double.isFinite(bound))result.upperBound=Math.min(in.boxUpper,bound);
                } catch(Exception ignored) { /* boxUpper remains valid */ }
            }
            double tolerance=certificateTolerance(result.lowerBound);
            if(result.upperBound<result.lowerBound-tolerance)
                throw new IllegalStateException("Risk maximization bound contradicts feasible scenario");
            result.upperBound=Math.max(result.upperBound,result.lowerBound);
            result.optimal=solved && cp.getStatus()==IloCplex.Status.Optimal
                    && result.upperBound-result.lowerBound<=tolerance;
        } catch(Exception error) {
            result.optimal=false;
            result.status="ERROR_"+error.getClass().getSimpleName();
            result.message=error.getMessage()==null?error.toString():error.getMessage();
            // Discard a failed solver's bound; the explicit all-upper box is safe.
            result.upperBound=in.boxUpper;
        } finally {
            if(cp!=null)cp.end();
            result.seconds=elapsed(started);
        }
        return result;
    }

    private static IloNumVar binaryProduct(IloCplex cp,IloIntVar b,IloNumVar v,String name) throws Exception {
        IloNumVar p=cp.numVar(0.0,1.0,name);
        cp.addLe(p,b); cp.addLe(p,v);
        cp.addGe(p,cp.diff(cp.sum(b,v),1.0));
        return p;
    }

    private static void project(double[] values,double budget) {
        double sum=0;
        for(int i=0;i<values.length;i++) {
            if(!Double.isFinite(values[i]))throw new IllegalStateException("Nonfinite solver scenario");
            values[i]=Math.max(0.0,Math.min(1.0,values[i])); sum+=values[i];
        }
        if(sum>budget && sum>0) {
            // nextDown prevents a final summation roundoff from increasing the budget.
            double scale=budget==0?0:Math.nextDown(budget/sum);
            for(int i=0;i<values.length;i++)values[i]*=scale;
        }
    }

    private static double scenario(Input in,double[] u,double[] v,boolean original) {
        double value=in.nominal;
        for(Term t:in.terms)value+=t.k*t.r*u[t.task]+t.n*t.q*v[t.arc]
                +t.k*t.q*(original?u[t.task]*v[t.arc]:Math.min(u[t.task],v[t.arc]));
        finiteNonnegative(value,"scenario risk"); return value;
    }

    private static Double analyticalValue(Input in) {
        if(in.terms.isEmpty())return 0.0;
        double[] uc=new double[in.tasks], vc=new double[in.arcs];
        if(in.gammaU==0 || in.gammaU==in.tasks) {
            double u=in.gammaU==0?0:1, constant=0;
            for(Term t:in.terms) {
                constant+=(t.n+t.k*u)*t.r;
                vc[t.arc]+=(t.n+t.k*u)*t.q;
            }
            return constant+budgetSupport(vc,in.gammaV);
        }
        if(in.gammaV==0 || in.gammaV==in.arcs) {
            double v=in.gammaV==0?0:1, constant=0;
            for(Term t:in.terms) {
                constant+=t.n*(t.r+t.q*v);
                uc[t.task]+=t.k*(t.r+t.q*v);
            }
            return constant+budgetSupport(uc,in.gammaU);
        }
        boolean interaction=false;
        for(Term t:in.terms) {
            uc[t.task]+=t.k*t.r; vc[t.arc]+=t.n*t.q;
            interaction|=t.k*t.q>0;
        }
        return interaction?null:in.nominal+budgetSupport(uc,in.gammaU)+budgetSupport(vc,in.gammaV);
    }

    private static double budgetSupport(double[] coefficients,double budget) {
        Arrays.sort(coefficients);
        double value=0,remaining=budget;
        for(int i=coefficients.length-1;i>=0 && remaining>0;i--) {
            double take=Math.min(1,remaining); value+=take*coefficients[i]; remaining-=take;
        }
        return value;
    }

    /** Full-instance multiplicative bound; +Infinity means its finite assumptions fail. */
    public static double theoreticalFactor(DataCreator data) {
        int n=validateData(data), sCount=data.getStartPoints().size();
        double factor=1.0;
        for(int s=0;s<sCount;s++) {
            double ns=data.getMinTransportTasks().get(s), k=data.getTransportTaskBuffs().get(s);
            for(int i=0;i<n;i++)for(int j=0;j<n;j++)if(data.getGraph().get(i).get(j)==1) {
                double r=risk(data,s,i,j,false),q=risk(data,s,i,j,true);
                if(k==0 || q==0)continue;
                if(ns==0 || r==0)return Double.POSITIVE_INFINITY;
                double delta=k/ns,zeta=q/r,extra;
                if(Double.isInfinite(delta))extra=Double.isInfinite(zeta)?Double.POSITIVE_INFINITY:zeta;
                else if(Double.isInfinite(zeta))extra=delta;
                else {
                    double denom=Math.sqrt(1.0+delta)+Math.sqrt(1.0+zeta);
                    extra=(delta/denom)*(zeta/denom);
                }
                factor=Math.max(factor,Math.nextUp(1.0+extra));
            }
        }
        return factor;
    }

    public static double certificateTolerance(double value) {
        return Math.max(1e-6,1e-9*Math.max(1,Math.abs(value)));
    }

    private static Input readInput(DataCreator data,List<int[]> routes) {
        int n=validateData(data), count=data.getStartPoints().size();
        if(routes==null || routes.size()!=count)throw new IllegalArgumentException("Route count differs from task count");
        LinkedHashMap<Long,Integer> arcIds=new LinkedHashMap<>();
        ArrayList<Term> terms=new ArrayList<>();
        for(int s=0;s<count;s++) {
            int[] nodes=routes.get(s);
            if(nodes==null || nodes.length==0 || nodes[0]!=data.getStartPoints().get(s))
                throw new IllegalArgumentException("Route origin mismatch for task "+s);
            if(!data.getCandidatePoints().contains(nodes[nodes.length-1]))
                throw new IllegalArgumentException("Route endpoint is not a candidate facility for task "+s);
            HashSet<Integer> seen=new HashSet<>(); double duration=0;
            for(int k=0;k<nodes.length;k++) {
                int j=nodes[k];
                if(j<0 || j>=n || !seen.add(j))throw new IllegalArgumentException("Route is not a simple physical path for task "+s);
                if(k==0)continue;
                int i=nodes[k-1];
                if(data.getGraph().get(i).get(j)!=1)throw new IllegalArgumentException("Route uses nonexistent arc "+i+"-"+j);
                double time=entry(data.getNodeTime(),j,i,n,"travel time");
                duration+=time;
                long key=((long)i)*n+j;
                Integer id=arcIds.get(key);
                if(id==null){id=arcIds.size();arcIds.put(key,id);}
                terms.add(new Term(s,id,data.getMinTransportTasks().get(s),data.getTransportTaskBuffs().get(s),
                        risk(data,s,i,j,false),risk(data,s,i,j,true)));
            }
            if(duration>data.getTimeWindow().get(s)+1e-7)throw new IllegalArgumentException("Route exceeds time limit for task "+s);
        }
        return new Input(count,arcIds.size(),data.getQuantityRiskExpectation(),data.getRoadRiskExpectation(),terms);
    }

    private static int validateData(DataCreator data) {
        if(data==null || data.getGraph()==null || data.getGraph().isEmpty())throw new IllegalArgumentException("Missing network");
        int n=data.getGraph().size();
        for(int i=0;i<n;i++) {
            if(data.getGraph().get(i)==null || data.getGraph().get(i).size()!=n)throw new IllegalArgumentException("Nonsquare graph");
            for(int j=0;j<n;j++) {
                Integer a=data.getGraph().get(i).get(j);
                if(a==null || (a!=0 && a!=1) || (i==j && a!=0))throw new IllegalArgumentException("Invalid physical graph entry");
            }
        }
        if(data.getStartPoints()==null || data.getStartPoints().isEmpty())throw new IllegalArgumentException("Missing tasks");
        int count=data.getStartPoints().size();
        if(data.getCandidatePoints()==null || data.getCandidatePoints().isEmpty())throw new IllegalArgumentException("Missing candidates");
        for(int i:data.getCandidatePoints())if(i<0 || i>=n)throw new IllegalArgumentException("Invalid candidate node");
        if(data.getMinTransportTasks()==null || data.getMinTransportTasks().size()!=count
                || data.getTransportTaskBuffs()==null || data.getTransportTaskBuffs().size()!=count
                || data.getTimeWindow()==null || data.getTimeWindow().size()!=count)
            throw new IllegalArgumentException("Task parameter dimensions differ");
        for(int s=0;s<count;s++) {
            int i=data.getStartPoints().get(s);
            if(i<0 || i>=n)throw new IllegalArgumentException("Invalid task origin");
            finiteNonnegative(data.getMinTransportTasks().get(s),"N");
            finiteNonnegative(data.getTransportTaskBuffs().get(s),"K");
            finiteNonnegative(data.getTimeWindow().get(s),"W");
        }
        finiteNonnegative(data.getQuantityRiskExpectation(),"Gamma_u");
        finiteNonnegative(data.getRoadRiskExpectation(),"Gamma_v");
        if(data.getSourceMinRisks()!=null && data.getSourceMinRisks().size()!=count)
            throw new IllegalArgumentException("Task risk matrix count differs");
        if(data.getSourceRiskBuffs()!=null && data.getSourceRiskBuffs().size()!=count)
            throw new IllegalArgumentException("Task risk width matrix count differs");
        return n;
    }

    private static double risk(DataCreator data,int s,int i,int j,boolean width) {
        ArrayList<ArrayList<ArrayList<Double>>> byTask=width?data.getSourceRiskBuffs():data.getSourceMinRisks();
        List<? extends List<Double>> matrix=byTask==null
                ?(width?data.getRiskBuffs():data.getMinRisks()):byTask.get(s);
        return entry(matrix,i,j,data.getGraph().size(),width?"risk width":"nominal risk coefficient");
    }
    private static double entry(List<? extends List<Double>> a,int i,int j,int n,String name) {
        if(a==null || a.size()!=n || a.get(i)==null || a.get(i).size()!=n || a.get(i).get(j)==null)
            throw new IllegalArgumentException("Invalid "+name+" matrix");
        double v=a.get(i).get(j); finiteNonnegative(v,name); return v;
    }
    private static void finiteNonnegative(double v,String name) {
        if(!Double.isFinite(v) || v<0)throw new IllegalArgumentException("Invalid "+name+": "+v);
    }
    private static double elapsed(long started){return (System.nanoTime()-started)/1e9;}
}
