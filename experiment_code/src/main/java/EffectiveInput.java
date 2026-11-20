import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Reads and validates the HAZMAT_EFFECTIVE_INPUT_V1 study format. */
final class EffectiveInput {
    private EffectiveInput() {}
    public static int arcs(DataCreator d) {
        int a=0; for(List<Integer> row:d.getGraph()) for(int x:row) if(x==1)a++; return a;
    }
    public static double r(DataCreator d,int s,int u,int v){return d.getSourceMinRisks()==null?d.getMinRisks().get(u).get(v):d.getSourceMinRisks().get(s).get(u).get(v);}
    public static double q(DataCreator d,int s,int u,int v){return d.getSourceRiskBuffs()==null?d.getRiskBuffs().get(u).get(v):d.getSourceRiskBuffs().get(s).get(u).get(v);}
    private static ArrayList<ArrayList<Double>> zeros(int n){ArrayList<ArrayList<Double>> m=new ArrayList<>();for(int i=0;i<n;i++)m.add(new ArrayList<>(Collections.nCopies(n,0.)));return m;}
    public static DataCreator read(Path p)throws IOException {
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(Files.newInputStream(p)))){
            if(!in.readUTF().equals("HAZMAT_EFFECTIVE_INPUT_V1"))throw new IOException("Unsupported input format");
            int n=in.readInt(),s=in.readInt(),m=in.readInt(),a=in.readInt();DataCreator d=new DataCreator(n,1L);
            d.setGovernmentRoadBudget(in.readDouble());d.setQuantityRiskExpectation(in.readDouble());d.setRoadRiskExpectation(in.readDouble());d.setIncludeNominalRiskInObjective(in.readBoolean());
            ArrayList<Integer> candidates=new ArrayList<>(),sources=new ArrayList<>();ArrayList<Double> fees=new ArrayList<>(),N=new ArrayList<>(),K=new ArrayList<>(),W=new ArrayList<>();
            for(int k=0;k<m;k++){candidates.add(in.readInt());fees.add(in.readDouble());}
            for(int k=0;k<s;k++){sources.add(in.readInt());N.add(in.readDouble());K.add(in.readDouble());W.add(in.readDouble());}
            d.setCandidatePoints(candidates);d.setCandidateBuildWeights(fees);d.setStartPoints(sources);d.setMinTransportTasks(N);d.setTransportTaskBuffs(K);d.setTimeWindow(W);
            boolean specific=in.readBoolean();ArrayList<ArrayList<Integer>> graph=new ArrayList<>();for(int i=0;i<n;i++)graph.add(new ArrayList<>(Collections.nCopies(n,0)));
            ArrayList<ArrayList<Double>> C=zeros(n),T=zeros(n),I=zeros(n),L=zeros(n);
            ArrayList<ArrayList<ArrayList<Double>>> R=new ArrayList<>(),Q=new ArrayList<>();for(int k=0;k<(specific?s:1);k++){R.add(zeros(n));Q.add(zeros(n));}
            for(int e=0;e<a;e++){int u=in.readInt(),v=in.readInt();graph.get(u).set(v,1);C.get(u).set(v,in.readDouble());T.get(v).set(u,in.readDouble());I.get(u).set(v,in.readDouble());L.get(u).set(v,in.readDouble());for(int k=0;k<R.size();k++){R.get(k).get(u).set(v,in.readDouble());Q.get(k).get(u).set(v,in.readDouble());}}
            d.setGraph(graph);d.setNodeDistance(C);d.setNodeTime(T);d.setDedicatedLaneCostWeight(I);d.setDedicatedLaneBuildCostWeight(L);d.setMinRisks(R.get(0));d.setRiskBuffs(Q.get(0));if(specific){d.setSourceMinRisks(R);d.setSourceRiskBuffs(Q);}if(in.read()!=-1)throw new IOException("Trailing data");return d;
        }
    }
    public static String sha(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream i=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=i.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    public static void validate(DataCreator d) {
        int n=d.getGraph().size(),s=d.getStartPoints().size();if(n<1||s<1||d.getCandidatePoints().isEmpty())throw new IllegalArgumentException("Empty instance");
        for(double v:new double[]{d.getGovernmentRoadBudget(),d.getQuantityRiskExpectation(),d.getRoadRiskExpectation()})nonnegative(v);
        for(int c:d.getCandidatePoints())if(c<0||c>=n)throw new IllegalArgumentException("Invalid facility");
        if(new HashSet<>(d.getCandidatePoints()).size()!=d.getCandidatePoints().size())throw new IllegalArgumentException("Duplicate facilities");
        for(double f:d.getCandidateBuildWeights())nonnegative(f);
        for(int k=0;k<s;k++){int u=d.getStartPoints().get(k);if(u<0||u>=n)throw new IllegalArgumentException("Invalid origin");nonnegative(d.getMinTransportTasks().get(k));nonnegative(d.getTransportTaskBuffs().get(k));nonnegative(d.getTimeWindow().get(k));}
        for(int u=0;u<n;u++)for(int v=0;v<n;v++){
            int edge=d.getGraph().get(u).get(v);if((edge!=0&&edge!=1)||(u==v&&edge!=0))throw new IllegalArgumentException("Invalid graph");
            if(edge==1){nonnegative(d.getNodeDistance().get(u).get(v));nonnegative(d.getNodeTime().get(v).get(u));nonnegative(d.getDedicatedLaneCostWeight().get(u).get(v));nonnegative(d.getDedicatedLaneBuildCostWeight().get(u).get(v));for(int k=0;k<s;k++){nonnegative(r(d,k,u,v));nonnegative(q(d,k,u,v));}}
        }
    }
    private static void nonnegative(double v){if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Missing, negative or nonfinite effective input");}
}
