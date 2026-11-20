import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Post-solution evaluators. All routes contain physical nodes, including both
 * endpoints; a one-node route means treatment at the source facility. Risk
 * always includes the nominal term. Maximum-risk incumbents are LOWER bounds.
 * This class never changes the infrastructure solvers or their parameters. */
public final class UnifiedDesignEvaluator {
    private UnifiedDesignEvaluator() { }
    public static final double COST_TOLERANCE = 1e-5;
    private static final double FEASIBILITY_TOLERANCE = 1e-7;
    private static final double RISK_ABSOLUTE_GAP = 1e-4;

    public static final class Result {
        public boolean[] openFacilities;
        public boolean[][] reserved;
        public List<int[]> routes = new ArrayList<>();
        public double[] costMinima;
        public double[] costLowerBounds;
        public double[] costUpperBounds;
        public String[] costStatuses;
        public double riskValue = Double.NaN;
        public double riskLowerBound = Double.NaN;
        public double riskUpperBound = Double.NaN;
        public double selectedRisk = Double.NaN;
        public double selectedRiskLowerBound = Double.NaN;
        public double selectedRiskUpperBound = Double.NaN;
        public boolean selectedRiskOptimal;
        public double facilityCost = Double.NaN;
        public double trafficImpact = Double.NaN;
        public double buildCost = Double.NaN;
        public double nominalRisk = Double.NaN;
        public double objectiveLowerBound = Double.NaN;
        public double objectiveUpperBound = Double.NaN;
        public double seconds;
        public boolean routesCertified;
        public boolean budgetFeasible;
        public boolean riskOptimal;
        public boolean optimal;
        public String status = "NOT_STARTED";
        public String message = "";
    }

    /** Exact carrier cost audit of any fixed design, including an SL design.
     * This does not assume an input routing plan is already cost-optimal and
     * does not solve a risk maximization problem. */
    public static Result carrierCosts(DataCreator data,boolean[] openFacilities,
                                      boolean[][] reserved,double costSeconds) {
        long started=System.nanoTime();checkSeconds(costSeconds);Network net=new Network(data);
        checkDesign(net,openFacilities,reserved);Result out=empty(net);
        out.openFacilities=openFacilities.clone();out.reserved=copy(reserved);account(data,net,out);
        if(certifyCosts(data,net,openFacilities,reserved,costSeconds,out))out.status="COST_CERTIFIED";
        return finish(out,started);
    }

    /** Every task first chooses a cheapest time-feasible path on the full graph.
     * Only the union of selected roads and terminal facilities is then charged.
     * The exact oracle has fixed edge order and deterministic single-threaded
     * settings; no risk criterion is used to choose among its cheapest paths. */
    public static Result sequential(DataCreator data, double costSeconds, double riskSeconds) {
        long started = System.nanoTime();
        checkSeconds(costSeconds); checkSeconds(riskSeconds);
        Network net = new Network(data);
        Result out = empty(net);
        boolean[] allFacilities = new boolean[net.m]; Arrays.fill(allFacilities, true);
        boolean[][] allRoads = new boolean[net.n][net.n];
        for (int e=0;e<net.physicalCount;e++) allRoads[net.u[e]][net.v[e]]=true;
        if (!certifyCosts(data, net, allFacilities, allRoads, costSeconds, out))
            return finish(out, started);
        for (int[] route : out.routes) {
            out.openFacilities[net.facilityIndex(route[route.length-1])] = true;
            for (int k=1;k<route.length;k++) out.reserved[route[k-1]][route[k]]=true;
        }
        account(data, net, out);
        UnifiedRiskEvaluator.Result risk = UnifiedRiskEvaluator.evaluateConservative(data, out.routes, riskSeconds);
        applyRisk(out, risk);
        out.optimal = out.routesCertified && out.budgetFeasible && out.riskOptimal;
        out.status = !out.budgetFeasible ? "BUDGET_INFEASIBLE"
                : out.riskOptimal ? "CERTIFIED_SEQUENTIAL" : "RISK_UNRESOLVED";
        out.message = "Sequential planning; carrier paths certified; risk=" + risk.status;
        return finish(out, started);
    }

    /** Maximize the common conservative robust risk over cost-optimal joint
     * responses to a FIXED design. No reservation-use/coverage constraint is
     * added: an alternative response may leave a reserved road unused. */
    public static Result worstTie(DataCreator data, boolean[] openFacilities,
                                  boolean[][] reserved, List<int[]> selectedRoutes,
                                  double costSeconds, double riskSeconds) {
        long started = System.nanoTime();
        checkSeconds(costSeconds); checkSeconds(riskSeconds);
        Network net = new Network(data);
        checkDesign(net, openFacilities, reserved);
        validateRoutes(data, net, openFacilities, reserved, selectedRoutes);
        Result out = empty(net);
        out.openFacilities = openFacilities.clone(); out.reserved = copy(reserved);
        account(data, net, out);
        if (!out.budgetFeasible) {
            out.status = "INPUT_DESIGN_BUDGET_INFEASIBLE";
            return finish(out, started);
        }
        if (!certifyCosts(data, net, openFacilities, reserved, costSeconds, out))
            return finish(out, started);
        for (int s=0;s<net.s;s++) {
            double selectedCost=routeCost(data, selectedRoutes.get(s));
            if (Math.abs(selectedCost-out.costMinima[s])>COST_TOLERANCE) {
                out.status="SELECTED_RESPONSE_NOT_COST_OPTIMAL";
                out.message="Task "+s+" has selected cost "+selectedCost+" and minimum "+out.costMinima[s];
                out.routesCertified=false;
                return finish(out,started);
            }
        }

        long riskStarted=System.nanoTime();
        UnifiedRiskEvaluator.Result selected=UnifiedRiskEvaluator.evaluateConservative(
                data, selectedRoutes, Math.min(60.0, Math.max(0.0,riskSeconds*0.2)));
        out.selectedRisk=selected.value; out.selectedRiskLowerBound=selected.lowerBound;
        out.selectedRiskUpperBound=selected.upperBound; out.selectedRiskOptimal=selected.optimal;
        // Certified cheapest paths with u=v=0 give an unconditional feasible
        // lower bound even if the selected route's LP or the MILP is unfinished.
        out.nominalRisk=nominal(data,net,out.routes);
        out.riskValue=out.nominalRisk; out.riskLowerBound=out.nominalRisk;
        out.riskUpperBound=fullBoxBound(data,net,reserved);
        objectiveBounds(out);
        if (remaining(riskStarted,riskSeconds)<=0.0) {
            out.status="WORST_TIE_NO_TIME"; return finish(out,started);
        }

        IloCplex cplex=null;
        try {
            cplex=new IloCplex(); configure(cplex);
            IloIntVar[][] x=new IloIntVar[net.s][net.e];
            boolean[] allowed=net.allowed(openFacilities,reserved);
            for(int s=0;s<net.s;s++) {
                for(int e=0;e<net.e;e++) {
                    x[s][e]=cplex.boolVar("tie_x_"+s+"_"+e);
                    if(!allowed[e])x[s][e].setUB(0.0);
                }
                addPathConstraints(cplex,data,net,x[s],s,out.costMinima[s]);
            }
            IloNumVar[] u=new IloNumVar[net.s];
            IloNumVar[] v=new IloNumVar[net.physicalCount];
            IloLinearNumExpr bu=cplex.linearNumExpr(),bv=cplex.linearNumExpr();
            for(int s=0;s<net.s;s++){u[s]=cplex.numVar(0,1,"tie_u_"+s);bu.addTerm(1,u[s]);}
            for(int e=0;e<net.physicalCount;e++){v[e]=cplex.numVar(0,1,"tie_v_"+e);bv.addTerm(1,v[e]);}
            cplex.addLe(bu,data.getQuantityRiskExpectation(),"tie_budget_u");
            cplex.addLe(bv,data.getRoadRiskExpectation(),"tie_budget_v");
            IloLinearNumExpr obj=cplex.linearNumExpr();
            for(int s=0;s<net.s;s++) {
                double ns=data.getMinTransportTasks().get(s),ks=data.getTransportTaskBuffs().get(s);
                for(int e=0;e<net.physicalCount;e++) {
                    if(!allowed[e])continue;
                    double r=risk(data,s,net.u[e],net.v[e],false),q=risk(data,s,net.u[e],net.v[e],true);
                    if(ns*r!=0)obj.addTerm(ns*r,x[s][e]);
                    if(ks*r!=0)obj.addTerm(ks*r,product(cplex,x[s][e],u[s],"ux_"+s+"_"+e));
                    if(ns*q!=0)obj.addTerm(ns*q,product(cplex,x[s][e],v[e],"vx_"+s+"_"+e));
                    if(ks*q!=0) {
                        IloNumVar w=cplex.numVar(0,1,"tie_w_"+s+"_"+e);
                        cplex.addLe(w,u[s]);cplex.addLe(w,v[e]);
                        obj.addTerm(ks*q,product(cplex,x[s][e],w,"wx_"+s+"_"+e));
                    }
                }
            }
            cplex.addMaximize(obj);
            double left=remaining(riskStarted,riskSeconds);
            if(left<=0){out.status="WORST_TIE_NO_TIME_BEFORE_SOLVE";return finish(out,started);}
            cplex.setParam(IloCplex.Param.TimeLimit,left);
            boolean found=cplex.solve();
            IloCplex.Status state=cplex.getStatus();
            out.status="WORST_TIE_"+state+"/"+cplex.getCplexStatus();
            if(state==IloCplex.Status.Infeasible || state==IloCplex.Status.Unbounded || state==IloCplex.Status.InfeasibleOrUnbounded)
                throw new IllegalStateException("Certified cheapest responses should make bounded worst-tie model feasible: "+state);
            try {
                double bound=cplex.getBestObjValue();
                if(Double.isFinite(bound))out.riskUpperBound=Math.min(out.riskUpperBound,bound);
            }catch(Exception unavailable){out.message="MILP upper bound unavailable; retained full-box bound.";}
            if(found) {
                ArrayList<int[]> candidateRoutes=new ArrayList<>();
                for(int s=0;s<net.s;s++)candidateRoutes.add(extract(net,data.getStartPoints().get(s),cplex.getValues(x[s])));
                validateRoutes(data,net,openFacilities,reserved,candidateRoutes);
                for(int s=0;s<net.s;s++)if(Math.abs(routeCost(data,candidateRoutes.get(s))-out.costMinima[s])>COST_TOLERANCE)
                    throw new IllegalStateException("Worst-tie route violates exact cost equality for task "+s);
                double value=cplex.getObjValue();
                if(!Double.isFinite(value))throw new IllegalStateException("Nonfinite MILP incumbent");
                if(value>=out.riskLowerBound-FEASIBILITY_TOLERANCE) {
                    out.riskValue=value;out.riskLowerBound=Math.max(out.riskLowerBound,value);
                    out.routes=candidateRoutes;out.nominalRisk=nominal(data,net,candidateRoutes);
                }
            }
            if(out.riskLowerBound>out.riskUpperBound+RISK_ABSOLUTE_GAP)
                throw new IllegalStateException("Worst-tie lower bound exceeds upper bound");
            // Raising a numerically crossed upper bound is conservative.
            out.riskUpperBound=Math.max(out.riskLowerBound,out.riskUpperBound);
            out.riskOptimal=found && out.riskUpperBound-out.riskLowerBound<=RISK_ABSOLUTE_GAP;
            out.optimal=out.routesCertified && out.budgetFeasible && out.riskOptimal;
            objectiveBounds(out);
        }catch(Exception error) {
            out.optimal=false;out.riskOptimal=false;
            out.status="WORST_TIE_ERROR";out.message=error.getClass().getSimpleName()+": "+error.getMessage();
            // The nominal cheapest response and the box bound remain valid.
            out.riskUpperBound=fullBoxBound(data,net,reserved);
            objectiveBounds(out);
        }finally {if(cplex!=null)cplex.end();}
        return finish(out,started);
    }

    private static Result empty(Network net) {
        Result out=new Result();out.openFacilities=new boolean[net.m];out.reserved=new boolean[net.n][net.n];
        out.costMinima=new double[net.s];Arrays.fill(out.costMinima,Double.NaN);
        out.costLowerBounds=new double[net.s];Arrays.fill(out.costLowerBounds,Double.NaN);
        out.costUpperBounds=new double[net.s];Arrays.fill(out.costUpperBounds,Double.NaN);
        out.costStatuses=new String[net.s];Arrays.fill(out.costStatuses,"NOT_RUN");return out;
    }

    private static boolean certifyCosts(DataCreator data,Network net,boolean[] open,boolean[][] roads,double seconds,Result out) {
        long started=System.nanoTime();boolean[] allowed=net.allowed(open,roads);
        for(int s=0;s<net.s;s++) {
            BpaExactPathPricing.Result r=BpaExactPathPricing.solve(net.n,data.getStartPoints().get(s),net.u,net.v,
                    net.cost,net.time,data.getTimeWindow().get(s),allowed,new boolean[net.e],new ArrayList<>(),remaining(started,seconds));
            out.costStatuses[s]=r.status;
            out.costLowerBounds[s]=r.lowerBound;
            if(r.pathEdges!=null&&Double.isFinite(r.objective))out.costUpperBounds[s]=r.objective;
            if(r.infeasible){out.status="CARRIER_INFEASIBLE";out.message="Task "+s;return false;}
            if(r.pathEdges==null || !Double.isFinite(r.lowerBound) || !Double.isFinite(r.objective)
                    || r.objective-r.lowerBound>COST_TOLERANCE || r.lowerBound-r.objective>COST_TOLERANCE) {
                out.status="COST_UNRESOLVED";out.message="Task "+s+": "+r.status;return false;
            }
            out.costMinima[s]=r.objective;
            double[] selected=new double[net.e];for(int e:r.pathEdges)selected[e]=1;
            out.routes.add(extract(net,data.getStartPoints().get(s),selected));
        }
        validateRoutes(data,net,open,roads,out.routes);
        out.routesCertified=true;return true;
    }

    private static void addPathConstraints(IloCplex cp,DataCreator d,Network net,IloIntVar[] x,int s,double target)throws Exception {
        int source=d.getStartPoints().get(s);
        for(int node=0;node<=net.n;node++) {
            IloLinearNumExpr balance=cp.linearNumExpr(),in=cp.linearNumExpr(),out=cp.linearNumExpr();
            for(int e=0;e<net.e;e++) {
                if(net.u[e]==node){balance.addTerm(1,x[e]);out.addTerm(1,x[e]);}
                if(net.v[e]==node){balance.addTerm(-1,x[e]);in.addTerm(1,x[e]);}
            }
            cp.addEq(balance,node==source?1:node==net.n?-1:0);cp.addLe(in,1);cp.addLe(out,1);
        }
        IloNumVar[] rank=new IloNumVar[net.n];
        for(int i=0;i<net.n;i++)rank[i]=cp.numVar(0,i==source?0:net.n-1,"tie_rank_"+s+"_"+i);
        for(int e=0;e<net.physicalCount;e++) {
            IloLinearNumExpr order=cp.linearNumExpr();order.addTerm(1,rank[net.v[e]]);order.addTerm(-1,rank[net.u[e]]);order.addTerm(-net.n,x[e]);
            cp.addGe(order,1-net.n);
        }
        IloLinearNumExpr time=cp.linearNumExpr(),cost=cp.linearNumExpr();
        for(int e=0;e<net.e;e++){time.addTerm(net.time[e],x[e]);cost.addTerm(net.cost[e],x[e]);}
        cp.addLe(time,d.getTimeWindow().get(s));cp.addEq(cost,target,"tie_exact_carrier_cost_"+s);
    }
    private static IloNumVar product(IloCplex cp,IloIntVar x,IloNumVar y,String name)throws Exception {
        IloNumVar p=cp.numVar(0,1,name);cp.addLe(p,x);cp.addLe(p,y);
        IloLinearNumExpr lower=cp.linearNumExpr();lower.addTerm(1,p);lower.addTerm(-1,x);lower.addTerm(-1,y);cp.addGe(lower,-1);return p;
    }
    private static void configure(IloCplex cp)throws Exception {
        cp.setOut(null);cp.setParam(IloCplex.Param.Threads,1);cp.setParam(IloCplex.Param.RandomSeed,0);
        cp.setParam(IloCplex.Param.MIP.Tolerances.MIPGap,0.0);cp.setParam(IloCplex.Param.MIP.Tolerances.AbsMIPGap,1e-7);
        cp.setParam(IloCplex.Param.MIP.Tolerances.Integrality,1e-9);cp.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility,1e-9);
        cp.setParam(IloCplex.Param.Simplex.Tolerances.Optimality,1e-9);
    }
    private static int[] extract(Network net,int source,double[] values) {
        int count=0;for(double x:values){if(!Double.isFinite(x)||Math.abs(x-Math.rint(x))>1e-6)throw new IllegalStateException("Fractional route");if(x>.5)count++;}
        ArrayList<Integer> nodes=new ArrayList<>();HashSet<Integer> seen=new HashSet<>();int cur=source,used=0;nodes.add(cur);seen.add(cur);
        while(cur!=net.n) {
            int edge=-1;
            for(int e=0;e<net.e;e++)if(net.u[e]==cur&&values[e]>.5){if(edge>=0)throw new IllegalStateException("Branched route");edge=e;}
            if(edge<0)throw new IllegalStateException("Broken route");cur=net.v[edge];used++;
            if(cur!=net.n){if(!seen.add(cur))throw new IllegalStateException("Cyclic route");nodes.add(cur);}
            if(used>net.n)throw new IllegalStateException("Route too long");
        }
        if(used!=count)throw new IllegalStateException("Disconnected selected arcs");
        int[] out=new int[nodes.size()];for(int i=0;i<out.length;i++)out[i]=nodes.get(i);return out;
    }
    private static void validateRoutes(DataCreator d,Network net,boolean[] open,boolean[][] roads,List<int[]> routes) {
        if(routes==null||routes.size()!=net.s)throw new IllegalArgumentException("One route per task is required");
        for(int s=0;s<net.s;s++) {
            int[] route=routes.get(s);if(route==null||route.length==0||route[0]!=d.getStartPoints().get(s))throw new IllegalArgumentException("Invalid source at task "+s);
            HashSet<Integer> seen=new HashSet<>();double time=0;
            for(int k=0;k<route.length;k++) {
                int v=route[k];if(v<0||v>=net.n||!seen.add(v))throw new IllegalArgumentException("Invalid or repeated physical node");
                if(k>0){int u=route[k-1];if(d.getGraph().get(u).get(v)!=1||!roads[u][v])throw new IllegalArgumentException("Unavailable route arc");time+=d.getNodeTime().get(v).get(u);}
            }
            int m=net.facilityIndex(route[route.length-1]);if(m<0||!open[m])throw new IllegalArgumentException("Route ends at unavailable facility");
            if(time>d.getTimeWindow().get(s)+FEASIBILITY_TOLERANCE)throw new IllegalArgumentException("Route exceeds time window");
        }
    }
    private static void checkDesign(Network net,boolean[] open,boolean[][] roads) {
        if(open==null||open.length!=net.m||roads==null||roads.length!=net.n)throw new IllegalArgumentException("Design dimensions");
        for(int i=0;i<net.n;i++){if(roads[i]==null||roads[i].length!=net.n)throw new IllegalArgumentException("Design dimensions");
            for(int j=0;j<net.n;j++)if(roads[i][j]&&!net.physical[i][j])throw new IllegalArgumentException("Reserved non-road");}
    }
    private static void account(DataCreator d,Network net,Result out) {
        out.facilityCost=0;out.trafficImpact=0;out.buildCost=0;
        for(int m=0;m<net.m;m++)if(out.openFacilities[m])out.facilityCost+=d.getCandidateBuildWeights().get(m);
        for(int e=0;e<net.physicalCount;e++)if(out.reserved[net.u[e]][net.v[e]]) {
            out.trafficImpact+=d.getDedicatedLaneCostWeight().get(net.u[e]).get(net.v[e]);
            out.buildCost+=d.getDedicatedLaneBuildCostWeight().get(net.u[e]).get(net.v[e]);
        }
        out.budgetFeasible=out.buildCost<=d.getGovernmentRoadBudget()+FEASIBILITY_TOLERANCE;
    }
    private static void applyRisk(Result out,UnifiedRiskEvaluator.Result risk) {
        out.riskValue=risk.value;out.riskLowerBound=risk.lowerBound;out.riskUpperBound=risk.upperBound;
        out.nominalRisk=risk.nominalRisk;out.riskOptimal=risk.optimal;objectiveBounds(out);
    }
    private static void objectiveBounds(Result out) {
        out.objectiveLowerBound=out.facilityCost+out.trafficImpact+out.riskLowerBound;
        out.objectiveUpperBound=out.facilityCost+out.trafficImpact+out.riskUpperBound;
    }
    private static double nominal(DataCreator d,Network net,List<int[]> routes) {
        double sum=0;for(int s=0;s<net.s;s++){int[] p=routes.get(s);for(int k=1;k<p.length;k++)sum+=d.getMinTransportTasks().get(s)*risk(d,s,p[k-1],p[k],false);}return sum;
    }
    private static double fullBoxBound(DataCreator d,Network net,boolean[][] roads) {
        double sum=0;for(int s=0;s<net.s;s++)for(int e=0;e<net.physicalCount;e++)if(roads[net.u[e]][net.v[e]])
            sum+=(d.getMinTransportTasks().get(s)+d.getTransportTaskBuffs().get(s))*(risk(d,s,net.u[e],net.v[e],false)+risk(d,s,net.u[e],net.v[e],true));return sum;
    }
    private static double routeCost(DataCreator d,int[] p){double c=0;for(int k=1;k<p.length;k++)c+=d.getNodeDistance().get(p[k-1]).get(p[k]);return c;}
    private static double risk(DataCreator d,int s,int u,int v,boolean width) {
        List<? extends List<? extends List<Double>>> specific=width?d.getSourceRiskBuffs():d.getSourceMinRisks();
        if(specific!=null&&!specific.isEmpty())return specific.get(s).get(u).get(v);
        return (width?d.getRiskBuffs():d.getMinRisks()).get(u).get(v);
    }
    private static boolean[][] copy(boolean[][] x){boolean[][] out=new boolean[x.length][];for(int i=0;i<x.length;i++)out[i]=x[i].clone();return out;}
    private static Result finish(Result out,long started){out.seconds=(System.nanoTime()-started)/1e9;return out;}
    private static double remaining(long started,double budget){return Math.max(0,budget-(System.nanoTime()-started)/1e9);}
    private static void checkSeconds(double x){if(!Double.isFinite(x)||x<0)throw new IllegalArgumentException("Invalid time budget");}
    private static void nonnegative(double x,String name){if(!Double.isFinite(x)||x<0)throw new IllegalArgumentException("Invalid "+name);}

    private static final class Network {
        final int n,s,m,e,physicalCount;final int[] u,v,facilities;final double[] cost,time;final boolean[][] physical;
        Network(DataCreator d) {
            n=d.getGraph().size();s=d.getStartPoints().size();m=d.getCandidatePoints().size();
            if(n<1||s<1||m<1)throw new IllegalArgumentException("Empty network/tasks/facilities");
            physical=new boolean[n][n];ArrayList<Integer> eu=new ArrayList<>(),ev=new ArrayList<>();
            ArrayList<Double> ec=new ArrayList<>(),et=new ArrayList<>();
            for(int i=0;i<n;i++)for(int j=0;j<n;j++)if(d.getGraph().get(i).get(j)==1){
                if(i==j)throw new IllegalArgumentException("Physical self-loop");physical[i][j]=true;eu.add(i);ev.add(j);
                double c=d.getNodeDistance().get(i).get(j),t=d.getNodeTime().get(j).get(i);nonnegative(c,"cost");nonnegative(t,"time");ec.add(c);et.add(t);
                nonnegative(d.getDedicatedLaneCostWeight().get(i).get(j),"impact");nonnegative(d.getDedicatedLaneBuildCostWeight().get(i).get(j),"build cost");
            }
            physicalCount=eu.size();facilities=new int[m];HashSet<Integer> unique=new HashSet<>();
            for(int k=0;k<m;k++){int node=d.getCandidatePoints().get(k);if(node<0||node>=n||!unique.add(node))throw new IllegalArgumentException("Invalid facility");
                facilities[k]=node;eu.add(node);ev.add(n);ec.add(0.0);et.add(0.0);nonnegative(d.getCandidateBuildWeights().get(k),"facility cost");}
            e=eu.size();u=new int[e];v=new int[e];cost=new double[e];time=new double[e];
            for(int k=0;k<e;k++){u[k]=eu.get(k);v[k]=ev.get(k);cost[k]=ec.get(k);time[k]=et.get(k);}
            nonnegative(d.getQuantityRiskExpectation(),"demand budget");nonnegative(d.getRoadRiskExpectation(),"risk budget");nonnegative(d.getGovernmentRoadBudget(),"build budget");
            for(int a=0;a<s;a++){int source=d.getStartPoints().get(a);if(source<0||source>=n)throw new IllegalArgumentException("Invalid source");
                nonnegative(d.getTimeWindow().get(a),"time window");nonnegative(d.getMinTransportTasks().get(a),"demand");nonnegative(d.getTransportTaskBuffs().get(a),"demand width");
                for(int k=0;k<physicalCount;k++){nonnegative(risk(d,a,u[k],v[k],false),"risk");nonnegative(risk(d,a,u[k],v[k],true),"risk width");}}
        }
        boolean[] allowed(boolean[] open,boolean[][] roads){boolean[] a=new boolean[e];for(int k=0;k<physicalCount;k++)a[k]=roads[u[k]][v[k]];for(int k=0;k<m;k++)a[physicalCount+k]=open[k];return a;}
        int facilityIndex(int node){for(int i=0;i<m;i++)if(facilities[i]==node)return i;return -1;}
    }
}
