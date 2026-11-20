import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Frozen, lossless effective inputs for the approved unified rerun. No solver calls. */
public final class UnifiedExperimentData {
    private UnifiedExperimentData() {}
    static Path CONVERTED;
    static Path IMPACT;
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected converted-data, impact CSV and output directories");
        CONVERTED = Paths.get(args[0]);
        IMPACT = Paths.get(args[1]);
        prepare(Paths.get(args[2]));
    }
    public static final class Spec {
        public String id, family, source, variant="base", taskRecords="";
        public long seed;
        public DataCreator data;
        Spec(String id,String family,String source,long seed,DataCreator data) {
            this.id=id; this.family=family; this.source=source; this.seed=seed; this.data=data;
        }
    }
    public static DataCreator loadOriginal(String id) throws Exception {
        DataCreator d=EmInstances.loadCase(CONVERTED.resolve(id));
        normalize(d); return d;
    }
    static DataCreator copy(DataCreator d) throws Exception {
        return EmInstances.copyCase(d);
    }
    static void normalize(DataCreator d) {
        d.setIncludeNominalRiskInObjective(true);
        d.setQuantityRiskExpectation(.5*d.getStartPoints().size());
        d.setRoadRiskExpectation(.5*arcs(d));
    }
    public static int arcs(DataCreator d) {
        int a=0; for(List<Integer> row:d.getGraph()) for(int x:row) if(x==1)a++; return a;
    }
    static String joinIndices(List<Integer> ids) {
        StringJoiner s=new StringJoiner(";"); for(int i:ids)s.add(Integer.toString(i)); return s.toString();
    }
    static List<Integer> subsetIndices(int count,int size,long seed) {
        List<Integer> a=new ArrayList<>(); for(int i=0;i<count;i++)a.add(i);
        Collections.shuffle(a,new Random(seed)); a=new ArrayList<>(a.subList(0,size)); Collections.sort(a); return a;
    }
    public static List<String> pilotIds() {
        return Arrays.asList("R01_seed123","R13_seed123","R23_seed123","EM_SUB_s12_r1_m6","EM_SUB_s30_full_m6","EM_EXP_s120_m20");
    }
    public static List<String> mechanismIds() {
        List<String> ids=new ArrayList<>();
        for(int s:new int[]{12,18,24})for(int rep=1;rep<=5;rep++)ids.add("EM_SUB_s"+s+"_r"+rep+"_m6");
        ids.add("EM_SUB_s30_full_m6");
        for(int g:new int[]{1,8,13})for(int seed=123;seed<=127;seed++)ids.add(String.format(Locale.ROOT,"R%02d_seed%d",g,seed));
        return ids;
    }
    public static void prepare(Path root) throws Exception {
        Path manifest=root.resolve("inputs_manifest.csv");
        if(Files.exists(manifest)) throw new IllegalStateException("Inputs already frozen; do not overwrite "+manifest);
        Files.createDirectories(root.resolve("inputs"));
        List<String> rows=new ArrayList<>();
        rows.add("case_id,family,source,seed,variant,n,arcs,tasks,facilities,gamma_u,gamma_v,budget,task_records,source_facility_overlap,unreachable_tasks,input_sha256,input_file");
        int[][] sizes={{40,4,2},{60,6,3},{80,8,4},{100,10,5},{200,10,5},{400,20,10},{600,30,15},
            {40,4,2},{60,6,3},{80,8,4},{100,10,5},{150,12,6},
            {40,4,2},{60,6,3},{80,8,4},{100,10,5},{40,4,2},{60,6,3},{80,8,4},
            {200,20,10},{400,32,16},{200,20,10},{400,32,16}};
        for(int g=0;g<sizes.length;g++)for(int seed=123;seed<=132;seed++) {
            int[] a=sizes[g]; double density=g<7?(g<4?4.:8.)/(a[0]-1):g<12?.1:g<16?.2:g<19?.3:g<21?.4:.5;
            DataCreator d=ExperimentDataFactory.createPaperData(a[0],seed,a[1],a[2],density,.5,.5,1.,0.);
            normalize(d);
            Spec sp=new Spec(String.format(Locale.ROOT,"R%02d_seed%d",g+1,seed),"random","paper_generator",seed,d);
            sp.taskRecords="generated"; freeze(root,sp,rows);
            if(seed==132)System.out.println("Prepared random group R"+(g+1));
        }
        DataCreator base=loadOriginal("em_s30_m6");
        for(int s=12;s<=28;s+=2)for(int rep=1;rep<=5;rep++) {
            long seed=880000L+100L*s+rep;
            DataCreator d=EmInstances.subsetSources(base,s,seed);
            normalize(d); Spec sp=new Spec("EM_SUB_s"+s+"_r"+rep+"_m6","em_sample","EM_306.mat",seed,d);
            sp.taskRecords=joinIndices(subsetIndices(30,s,seed)); freeze(root,sp,rows);
        }
        Spec full=new Spec("EM_SUB_s30_full_m6","em_sample","EM_306.mat",0,copy(base));
        List<Integer> fullIds=new ArrayList<>();for(int i=0;i<30;i++)fullIds.add(i);full.taskRecords=joinIndices(fullIds);freeze(root,full,rows);
        for(int s:new int[]{30,36,42,48,60,80,100,120}) {
            long seed=993000L+37L*s+20;
            DataCreator d=EmInstances.composeEmExpandedCase(s,20); normalize(d);
            Spec sp=new Spec("EM_EXP_s"+s+"_m20","em_expanded","EM_306.mat+em_s12_m20",seed,d);
            List<Integer> ids=new ArrayList<>(fullIds);Random random=new Random(seed);for(int k=30;k<s;k++)ids.add(random.nextInt(30));
            sp.taskRecords=joinIndices(ids); freeze(root,sp,rows);
        }
        for(int s:new int[]{12,18,24}) {
            String original="em_s"+s+"_m6";DataCreator d=loadOriginal(original);
            List<Integer> rec=new ArrayList<>();for(int k=0;k<s;k++)rec.add(k);
            LinkedHashMap<String,double[]> variants=new LinkedHashMap<>();
            // impact multiplier, Gamma_u, Gamma_v, Q multiplier, build budget.
            variants.put("base",new double[]{1,.5*s,129,1,10000});
            for(double x:new double[]{.5,.75,1.25,1.5})variants.put("impact_"+x,new double[]{x,.5*s,129,1,10000});
            for(double x:new double[]{0,.1,.25,.75,1})variants.put("gamma_u_"+x,new double[]{1,x*s,129,1,10000});
            for(double x:new double[]{0,1,2,5,10,20,40,80,258})variants.put("gamma_v_"+x,new double[]{1,.5*s,x,1,10000});
            for(double x:new double[]{0,.5,1.5,2})variants.put("q_"+x,new double[]{1,.5*s,129,x,10000});
            double all=0; for(int u=0;u<74;u++)for(int v=0;v<74;v++)if(d.getGraph().get(u).get(v)==1)all+=d.getDedicatedLaneBuildCostWeight().get(u).get(v);
            for(double x:new double[]{0,250,500,750,1000,1500,2000,4000,all})variants.put("budget_"+x,new double[]{1,.5*s,129,1,x});
            if(variants.size()!=32)throw new AssertionError("Sensitivity count");
            for(Map.Entry<String,double[]> entry:variants.entrySet()) {
                double[] v=entry.getValue(); DataCreator c=copy(d);
                c.setQuantityRiskExpectation(v[1]);c.setRoadRiskExpectation(v[2]);c.setGovernmentRoadBudget(v[4]);
                c.setDedicatedLaneCostWeight(scale(d.getDedicatedLaneCostWeight(),v[0]));
                ArrayList<ArrayList<ArrayList<Double>>> q=new ArrayList<>();
                for(int k=0;k<s;k++)q.add(scale(d.getSourceRiskBuffs().get(k),v[3]));
                c.setSourceRiskBuffs(q);c.setRiskBuffs(q.get(0));
                Spec sp=new Spec("EM_ORIG_s"+s+"_m6__"+entry.getKey(),"sensitivity",original,0,c);
                sp.variant=entry.getKey();sp.taskRecords=joinIndices(rec);freeze(root,sp,rows);
            }
        }
        if(rows.size()!=381)throw new AssertionError("Expected 380 configurations, got "+(rows.size()-1));
        Files.write(manifest,rows,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("mechanism_case_ids.txt"),mechanismIds(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("pilot_case_ids.txt"),pilotIds(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        List<String> evals=new ArrayList<>();evals.add("case_id,operation,gamma_u,gamma_v,fixed_route_source");
        for(int s:new int[]{12,18,24}){
            String id="EM_ORIG_s"+s+"_m6__base";
            evals.add(id+",original_base,"+(.5*s)+",129,"+id);
            evals.add(id+",original_small,1,1,"+id);
            evals.add(id+",original_fractional,0.5,0.5,"+id);
        }
        Files.write(root.resolve("original_risk_evaluation_specs.csv"),evals,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println("Prepared and round-trip verified "+(rows.size()-1)+" frozen inputs; no optimization run.");
    }
    static ArrayList<ArrayList<Double>> scale(List<? extends List<Double>> x,double f) {
        ArrayList<ArrayList<Double>> r=new ArrayList<>();for(List<Double> row:x){ArrayList<Double> a=new ArrayList<>();for(double v:row)a.add(v*f);r.add(a);}return r;
    }
    static void freeze(Path root,Spec sp,List<String> rows) throws Exception {
        validate(sp.data);Path path=root.resolve("inputs").resolve(sp.id+".bin.gz");
        try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)))){write(sp.data,out);}
        DataCreator reread=read(path);validate(reread);
        if(!fingerprint(sp.data).equals(fingerprint(reread)))throw new AssertionError("Round-trip data mismatch "+sp.id);
        int overlap=0;for(int source:sp.data.getStartPoints())if(sp.data.getCandidatePoints().contains(source))overlap++;
        rows.add(String.join(",",sp.id,sp.family,sp.source,Long.toString(sp.seed),sp.variant,
            Integer.toString(sp.data.getGraph().size()),Integer.toString(arcs(sp.data)),Integer.toString(sp.data.getStartPoints().size()),Integer.toString(sp.data.getCandidatePoints().size()),
            Double.toString(sp.data.getQuantityRiskExpectation()),Double.toString(sp.data.getRoadRiskExpectation()),Double.toString(sp.data.getGovernmentRoadBudget()),
            sp.taskRecords,Integer.toString(overlap),Integer.toString(unreachable(sp.data)),sha(path),"inputs/"+path.getFileName()));
    }
    public static double r(DataCreator d,int s,int u,int v){return d.getSourceMinRisks()==null?d.getMinRisks().get(u).get(v):d.getSourceMinRisks().get(s).get(u).get(v);}
    public static double q(DataCreator d,int s,int u,int v){return d.getSourceRiskBuffs()==null?d.getRiskBuffs().get(u).get(v):d.getSourceRiskBuffs().get(s).get(u).get(v);}
    private static void write(DataCreator d,DataOutputStream o)throws IOException {
        int n=d.getGraph().size(),s=d.getStartPoints().size(),m=d.getCandidatePoints().size();o.writeUTF("HAZMAT_EFFECTIVE_INPUT_V1");o.writeInt(n);o.writeInt(s);o.writeInt(m);o.writeInt(arcs(d));
        o.writeDouble(d.getGovernmentRoadBudget());o.writeDouble(d.getQuantityRiskExpectation());o.writeDouble(d.getRoadRiskExpectation());o.writeBoolean(d.getIncludeNominalRiskInObjective());
        for(int k=0;k<m;k++){o.writeInt(d.getCandidatePoints().get(k));o.writeDouble(d.getCandidateBuildWeights().get(k));}
        for(int k=0;k<s;k++){o.writeInt(d.getStartPoints().get(k));o.writeDouble(d.getMinTransportTasks().get(k));o.writeDouble(d.getTransportTaskBuffs().get(k));o.writeDouble(d.getTimeWindow().get(k));}
        boolean specific=d.getSourceMinRisks()!=null||d.getSourceRiskBuffs()!=null;o.writeBoolean(specific);
        for(int u=0;u<n;u++)for(int v=0;v<n;v++)if(d.getGraph().get(u).get(v)==1){
            o.writeInt(u);o.writeInt(v);o.writeDouble(d.getNodeDistance().get(u).get(v));o.writeDouble(d.getNodeTime().get(v).get(u));o.writeDouble(d.getDedicatedLaneCostWeight().get(u).get(v));o.writeDouble(d.getDedicatedLaneBuildCostWeight().get(u).get(v));
            for(int k=0;k<(specific?s:1);k++){o.writeDouble(r(d,k,u,v));o.writeDouble(q(d,k,u,v));}
        }
    }
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
    static String fingerprint(DataCreator d)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(DataOutputStream o=new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),md))){write(d,o);}return hex(md.digest());}
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
    static int unreachable(DataCreator d){int bad=0,n=d.getGraph().size();for(int origin:new HashSet<>(d.getStartPoints())){boolean[] seen=new boolean[n];ArrayDeque<Integer> todo=new ArrayDeque<>();todo.add(origin);seen[origin]=true;while(!todo.isEmpty()){int u=todo.remove();for(int v=0;v<n;v++)if(d.getGraph().get(u).get(v)==1&&!seen[v]){seen[v]=true;todo.add(v);}}boolean ok=false;for(int f:d.getCandidatePoints())ok|=seen[f];if(!ok)for(int x:d.getStartPoints())if(x==origin)bad++;}return bad;}
}
