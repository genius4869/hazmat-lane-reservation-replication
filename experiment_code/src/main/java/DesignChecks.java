import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent feasibility and objective checks used by the study runner. */
final class DesignChecks {
    private DesignChecks() {}
    public static Map<String,Object> fields(Object o)throws Exception{Map<String,Object> m=new LinkedHashMap<>();for(Field f:o.getClass().getFields())if(!Modifier.isStatic(f.getModifiers()))m.put(f.getName(),f.get(o));return m;}
    static final class Design {boolean[] open;boolean[][] reserved;List<int[]> routes;}
    static Design design(DataCreator d,String facilities,String roads,List<int[]> routes){
        Design x=new Design();int n=d.getGraph().size();x.open=new boolean[d.getCandidatePoints().size()];x.reserved=new boolean[n][n];x.routes=routes;
        for(String f:facilities.split(";"))if(!f.isEmpty()){int node=Integer.parseInt(f),k=d.getCandidatePoints().indexOf(node);if(k<0)throw new IllegalArgumentException("Unknown facility");x.open[k]=true;}
        for(String e:roads.split(";"))if(!e.isEmpty()){String[] uv=e.split("-");x.reserved[Integer.parseInt(uv[0])][Integer.parseInt(uv[1])]=true;}
        return x;
    }
    static Map<String,Object> verify(DataCreator d,Design x,double reported,boolean carrier,double costLimit)throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();List<String> errors=new ArrayList<>();int n=d.getGraph().size(),s=d.getStartPoints().size();boolean[][] used=new boolean[n][n];double fee=0,impact=0,build=0;
        if(x.routes.size()!=s)throw new IllegalStateException("Missing task routes");
        for(int k=0;k<x.open.length;k++)if(x.open[k])fee+=d.getCandidateBuildWeights().get(k);
        for(int k=0;k<s;k++){
            int[] p=x.routes.get(k);if(p.length==0||p[0]!=d.getStartPoints().get(k))throw new IllegalStateException("Wrong task origin");
            int target=d.getCandidatePoints().indexOf(p[p.length-1]);if(target<0||!x.open[target])errors.add("closed endpoint task "+k);
            Set<Integer> seen=new HashSet<>();double duration=0;
            for(int j=0;j<p.length;j++){if(p[j]<0||p[j]>=n||!seen.add(p[j]))throw new IllegalStateException("Non-simple or invalid route");if(j==0)continue;int u=p[j-1],v=p[j];if(d.getGraph().get(u).get(v)!=1||!x.reserved[u][v])errors.add("unavailable arc task "+k);used[u][v]=true;duration+=d.getNodeTime().get(v).get(u);}
            if(duration>d.getTimeWindow().get(k)+1e-7)errors.add("time violation task "+k);
        }
        for(int u=0;u<n;u++)for(int v=0;v<n;v++)if(x.reserved[u][v]){if(d.getGraph().get(u).get(v)!=1||!used[u][v])errors.add("unused/nonphysical reserved arc "+u+"-"+v);impact+=d.getDedicatedLaneCostWeight().get(u).get(v);build+=d.getDedicatedLaneBuildCostWeight().get(u).get(v);}
        if(build>d.getGovernmentRoadBudget()+1e-7)errors.add("build budget violation");
        out.put("facility_cost",fee);out.put("traffic_impact",impact);out.put("build_cost",build);out.put("build_budget",d.getGovernmentRoadBudget());out.put("primal_verified",errors.isEmpty());
        UnifiedRiskEvaluator.Result risk=UnifiedRiskEvaluator.evaluateConservative(d,x.routes,60);
        out.put("risk",fields(risk));double tol=Math.max(1e-4,1e-8*Math.max(1,Math.abs(reported)));
        out.put("objective_recheck_tolerance",tol);
        if(risk.feasible&&reported<fee+impact+risk.lowerBound-tol)errors.add("reported objective below risk lower bound");
        if(risk.optimal&&Math.abs(reported-(fee+impact+risk.value))>tol)errors.add("objective components disagree");
        int completed=0;List<Map<String,Object>> costs=new ArrayList<>();long started=System.nanoTime();
        if(carrier)for(int k=0;k<s;k++){
            double remain=costLimit-(System.nanoTime()-started)/1e9;if(remain<=0)break;
            BpaExactPathPricing.Result c=cost(d,x,k,remain);double selected=0;int[] p=x.routes.get(k);for(int j=1;j<p.length;j++)selected+=d.getNodeDistance().get(p[j-1]).get(p[j]);
            Map<String,Object> item=new LinkedHashMap<>();item.put("task",k);item.put("selected",selected);item.put("minimum_incumbent",c.objective);item.put("minimum_lower_bound",c.lowerBound);item.put("status",c.status);costs.add(item);
            if(c.pathEdges!=null&&c.objective<selected-1e-5)errors.add("cheaper carrier response task "+k);
            if(c.infeasible)errors.add("selected route but oracle infeasible task "+k);
            if(Double.isFinite(c.lowerBound)&&c.lowerBound>=selected-1e-5)completed++;
        }
        out.put("carrier_required",carrier);out.put("carrier_tasks_checked",completed);out.put("carrier_checks",costs);out.put("errors",errors);
        out.put("status",!errors.isEmpty()?"FAIL":risk.optimal&&(!carrier||completed==s)?"PASS":"PARTIAL");return out;
    }
    static BpaExactPathPricing.Result cost(DataCreator d,Design x,int task,double seconds){
        int n=d.getGraph().size(),a=EffectiveInput.arcs(d),m=d.getCandidatePoints().size();int[] u=new int[a+m],v=new int[a+m];double[] c=new double[a+m],t=new double[a+m];boolean[] allow=new boolean[a+m],required=new boolean[a+m];int e=0;
        for(int i=0;i<n;i++)for(int j=0;j<n;j++)if(d.getGraph().get(i).get(j)==1){u[e]=i;v[e]=j;c[e]=d.getNodeDistance().get(i).get(j);t[e]=d.getNodeTime().get(j).get(i);allow[e]=x.reserved[i][j];e++;}
        for(int k=0;k<m;k++){u[e]=d.getCandidatePoints().get(k);v[e]=n;allow[e]=x.open[k];e++;}
        return BpaExactPathPricing.solve(n,d.getStartPoints().get(task),u,v,c,t,d.getTimeWindow().get(task),allow,required,new ArrayList<>(),seconds);
    }
    static void saveDesign(Path p,String hash,Design d)throws Exception{try(DataOutputStream o=new DataOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW))){o.writeUTF(hash);o.writeInt(d.open.length);for(boolean b:d.open)o.writeBoolean(b);o.writeInt(d.reserved.length);for(boolean[] row:d.reserved)for(boolean b:row)o.writeBoolean(b);o.writeInt(d.routes.size());for(int[] route:d.routes){o.writeInt(route.length);for(int x:route)o.writeInt(x);}}}
    static Design readDesign(Path p,String hash)throws Exception{try(DataInputStream i=new DataInputStream(Files.newInputStream(p))){if(!hash.equals(i.readUTF()))throw new IllegalArgumentException("Design belongs to another input");Design d=new Design();d.open=new boolean[i.readInt()];for(int k=0;k<d.open.length;k++)d.open[k]=i.readBoolean();int n=i.readInt();d.reserved=new boolean[n][n];for(int u=0;u<n;u++)for(int v=0;v<n;v++)d.reserved[u][v]=i.readBoolean();d.routes=new ArrayList<>();int s=i.readInt();for(int k=0;k<s;k++){int[] a=new int[i.readInt()];for(int j=0;j<a.length;j++)a[j]=i.readInt();d.routes.add(a);}return d;}}
    public static void writeJson(Path p,Object o)throws IOException {Files.writeString(p,json(o)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}
    public static String json(Object o){
        if(o==null)return "null";if(o instanceof Boolean)return o.toString();if(o instanceof Number){double v=((Number)o).doubleValue();return Double.isFinite(v)?o.toString():"null";}
        if(o instanceof Map){StringJoiner j=new StringJoiner(",","{","}");for(Map.Entry<?,?> e:((Map<?,?>)o).entrySet())j.add(json(e.getKey().toString())+":"+json(e.getValue()));return j.toString();}
        if(o instanceof Iterable){StringJoiner j=new StringJoiner(",","[","]");for(Object v:(Iterable<?>)o)j.add(json(v));return j.toString();}
        if(o.getClass().isArray()){StringJoiner j=new StringJoiner(",","[","]");for(int k=0;k<Array.getLength(o);k++)j.add(json(Array.get(o,k)));return j.toString();}
        StringBuilder b=new StringBuilder("\"");for(char c:o.toString().toCharArray()){if(c=='\"'||c=='\\')b.append('\\').append(c);else if(c=='\n')b.append("\\n");else if(c=='\r')b.append("\\r");else if(c=='\t')b.append("\\t");else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();
    }
}
