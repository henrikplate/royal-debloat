package se.kth.jdbl.pom;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.test.plugin.BuildTool;
import org.apache.maven.shared.test.plugin.ProjectTool;
import org.apache.maven.shared.test.plugin.RepositoryTool;
import org.apache.maven.shared.test.plugin.TestToolsException;
import org.codehaus.plexus.PlexusTestCase;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import se.kth.jdbl.pom.analysis.ClassFileVisitorUtils;
import se.kth.jdbl.pom.analysis.ProjectDependencyAnalysis;
import se.kth.jdbl.pom.analysis.ProjectDependencyAnalyzer;
import se.kth.jdbl.pom.analysis.ProjectDependencyAnalyzerException;
import se.kth.jdbl.pom.analysis.asm.DependencyClassFileVisitor;
import se.kth.jdbl.pom.counter.ClassMembersVisitorCounter;
import se.kth.jdbl.pom.util.*;

import java.io.*;
import java.math.BigInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App extends PlexusTestCase {

    //--------------------------/
    //------ CLASS FIELDS ------/
    //--------------------------/

    private static BuildTool buildTool;
    private static File localRepo;
    private static ProjectTool projectTool;
    private static ProjectDependencyAnalyzer analyzer;

    private static final Logger LOGGER = Logger.getLogger(App.class.getName());

    //--------------------------/
    //----- PUBLIC METHODS -----/
    //--------------------------/

    public static void main(String[] args) throws Exception {
        App app = new App();
        app.setUp();

        // read the list of artifacts
        BufferedReader br = new BufferedReader(new FileReader(new File("/home/cesarsv/Documents/xperiments/df_sample.csv")));

        // report results files
        String resultsDir = "/home/cesarsv/Documents/xperiments/results/";

        // directories to put the artifact and its dependencies
        String artifactDir = "/home/cesarsv/Documents/xperiments/artifact/";
        String dependenciesDir = localRepo.getAbsolutePath();

        BufferedWriter bwResults = new BufferedWriter(new FileWriter(resultsDir + "results.csv", true));
        BufferedWriter bwDescription = new BufferedWriter(new FileWriter(resultsDir + "description.csv", true));

        // write csv report headers
        bwDescription.write("Artifact,NbClasses,NbFields,NbMethods,NbAnnotations,Organization,Scm,Ci,License,Description" + "\n");
        bwResults.write("Artifact,AllDeps,Pack,Scope,Optional,Type,Used,Declared,NbTypes,NbFields,NbMethods,NbAnnotations,NbDeps,TreeLevel,InConflict" + "\n");

        bwResults.close();
        bwDescription.close();

        String artifact = br.readLine();

        // read the list of artifacts' coordinates to be analyzed
        while (artifact != null) {
            artifact = artifact.substring(1, artifact.length() - 1);
            String[] split = artifact.split(":");
            String groupId = split[0];
            String artifactId = split[1];
            String version = split[2];

            try {
                app.execute(groupId, artifactId, version, resultsDir, artifactDir, dependenciesDir);
            } catch (TestToolsException | ProjectDependencyAnalyzerException | IOException | XmlPullParserException e) {
                artifact = br.readLine();
                continue;
            }
            artifact = br.readLine();
        }
        br.close();
    }

    public static BuildTool getBuildTool() {
        return buildTool;
    }

    public static File getLocalRepo() {
        return localRepo;
    }

    //--------------------------/
    //---- PRIVATE METHODS -----/
    //--------------------------/

    @Override
    protected void setUp() throws Exception {

        super.setUp();

        buildTool = (BuildTool) lookup(BuildTool.ROLE);

        projectTool = (ProjectTool) lookup(ProjectTool.ROLE);

        if (localRepo == null) {

            RepositoryTool repositoryTool = (RepositoryTool) lookup(RepositoryTool.ROLE);
            localRepo = repositoryTool.findLocalRepositoryDirectory();

            // set a custom local maven repository

            localRepo = new File("/home/cesarsv/Documents/xperiments/dependencies");
            System.setProperty("maven.home", "/home/cesarsv/Documents/xperiments/dependencies");

            LOGGER.info("Local repository: " + localRepo);
        }
        analyzer = (ProjectDependencyAnalyzer) lookup(ProjectDependencyAnalyzer.ROLE);

    }

    private void execute(String groupId, String artifactId, String version, String resultsDir, String artifactDir, String dependenciesDir)
            throws TestToolsException, ProjectDependencyAnalyzerException, IOException, XmlPullParserException {

        MavenPluginInvoker mavenPluginInvoker = new MavenPluginInvoker();

        // remove the content of local directories
        FileUtils.cleanDirectory(new File(artifactDir));

        // set a size threshold of 10GB size (clean it if is larger that that)
        // checkDependenciesDirSize(dependenciesDir, new BigInteger("53687091200"); // 50GB

        String coordinates = groupId + ":" + artifactId + ":" + version;

        LOGGER.info("---------------------------------------------------------------------------------------------");
        LOGGER.log(Level.INFO, () -> "Processing: " + coordinates);
        LOGGER.info("---------------------------------------------------------------------------------------------");

        // download the artifact pom
        LOGGER.info("downloading pom");
        PomDownloader.downloadPom(artifactDir, groupId, artifactId, version);

        // copy the artifact locally
        LOGGER.info("copying artifact");
        mavenPluginInvoker.copyArtifact(artifactDir + "pom.xml", coordinates, artifactDir);

        // decompress the artifact locally if the jar file exists
        File jarFile = new File(artifactDir + artifactId + "-" + version + ".jar");
        if (jarFile.exists()) {

            // get basic dependency info from the dependency tree
            LOGGER.info("getting dependency tree");
            String dependencyTreePath = artifactDir + "dependencyTree.txt";
            mavenPluginInvoker.copyDependencyTree(artifactDir + "pom.xml", coordinates, dependencyTreePath);

            if (new File(dependencyTreePath).exists()) {

                // resolve all the dependencies
                LOGGER.info("resolving dependencies");
                mavenPluginInvoker.resolveDependencies(artifactDir + "pom.xml", coordinates);

                // copy all the dependencies locally
                LOGGER.info("copying dependencies");
                mavenPluginInvoker.copyDependencies(artifactDir + "pom.xml", coordinates, dependenciesDir);

                LOGGER.info("decompressing jar");
                JarUtils.decompressJarFile(artifactDir + "target/classes/", artifactDir + artifactId + "-" + version + ".jar");

                // build the maven project with its dependencies from the local repository
                LOGGER.info("building maven project");

                MavenProject mavenProject = null;
                try {
                    mavenProject = projectTool.readProjectWithDependencies(new File(artifactDir + "pom.xml"), localRepo);
                } catch (Exception e) {
                }

                if (mavenProject != null) { // the maven project was build correctly

                    Build build = new Build();
                    build.setDirectory(artifactDir);
                    mavenProject.setBuild(build);

                    DependencyTreeAnalyzer dta = new DependencyTreeAnalyzer(dependencyTreePath);

                    ArrayList<String> directDependencies = dta.getDirectDependencies();
                    ArrayList<String> allDependencies = dta.getAllDependencies();

                    LOGGER.info("analyzing dependencies usage");
                    ProjectDependencyAnalysis actualAnalysis = analyzer.analyze(mavenProject);
                    actualAnalysis.ignoreNonCompile();

                    // used and declared dependencies"
                    Set<Artifact> usedDeclaredDependencies = actualAnalysis.getUsedDeclaredArtifacts();

                    // used but not undeclared dependencies
                    Set<Artifact> usedUndeclaredDependencies = actualAnalysis.getUsedUndeclaredArtifacts();

                    // manipulation of the pom file
                    LOGGER.info("writing artifact description");
                    Model pomModel = PomManipulator.readModel(new File(artifactDir + "pom.xml"));

                    CustomFileWriter.writeArtifactProperties(resultsDir + "description.csv", pomModel, coordinates);

                    ArrayList<MavenDependency> dependencies = new ArrayList<>();
                    for (String dep : allDependencies) {

                        String inConflict = "NO";

                        String originalDep = dep;

                        if (dep.startsWith("(")) {
                            dep = dep.substring(1, dep.length() - 1);
                            String[] tmpSplit = dep.split(" - ");
                            dep = tmpSplit[0];
                            inConflict = tmpSplit[1]
                                    .replace(",", "[comma] ")
                                    .replace(";", "[comma] ");
                        }

                        dep = dep.split(" ")[0];// manage the case "junit:junit:3.8.1:test (scope not updated to compile)"
                        String[] split = dep.split(":");
                        String g = split[0];
                        String a = split[1];
                        String t = split[2];
                        String v = split[3];
                        String s = split[4].split(" ")[0];

                        boolean isOptional = false;
                        boolean isUsed = false;
                        boolean isDeclared = false;

                        for (Artifact usedDeclaredDependency : usedDeclaredDependencies) {
                            if (usedDeclaredDependency.toString().equals(dep)) {
                                isUsed = true;
                                isOptional = usedDeclaredDependency.isOptional();
                                break;
                            }
                        }

                        for (Artifact usedUndeclaredDependency : usedUndeclaredDependencies) {
                            if (usedUndeclaredDependency.toString().equals(dep)) {
                                isUsed = true;
                                isOptional = usedUndeclaredDependency.isOptional();
                                break;
                            }
                        }

                        List<Dependency> declaredDeps = pomModel.getDependencies();
                        for (Dependency declaredDep : declaredDeps) {
                            if (declaredDep.getGroupId().equals(g) &&
                                    declaredDep.getArtifactId().equals(a)) {
                                isDeclared = true;
                                break;
                            }
                        }

                        // bytecode class members counting
                        ClassMembersVisitorCounter.resetClassCounters();
                        File file = new File(dependenciesDir + "/" +
                                g.replace(".", "/") + "/" +
                                a + "/" +
                                v + "/" +
                                a + "-" +
                                v + ".jar");

                        if (file.exists()) {
                            URL url = file.toURI().toURL();
                            try {
                                ClassFileVisitorUtils.accept(url, new DependencyClassFileVisitor());
                            }catch (Exception e){
                                LOGGER.log(Level.WARNING, "Something happen with: " + file.getAbsolutePath());
                            }
                        }

                       /* System.out.println(ClassMembersVisitorCounter.getNbVisitedClasses());
                        System.out.println(ClassMembersVisitorCounter.getNbVisitedFields());
                        System.out.println(ClassMembersVisitorCounter.getNbVisitedMethods());
                        System.out.println(ClassMembersVisitorCounter.getNbVisitedAnnotations());*/

                        MavenDependency dependency = new MavenDependency();
                        dependency
                                .setCoordinates(g + ":" + a + ":" + v)
                                .setType(t)
                                .setScope(s)
                                .isOptional(isOptional)
                                .setDependencyType((directDependencies.contains(originalDep)) ? "direct" : "transitive")
                                .isUsed(isUsed)
                                .isDeclared(isDeclared)
                                .setTreeLevel(dta.getLevel(g, a, v))
                                .setNbTypes(ClassMembersVisitorCounter.getNbVisitedClasses())
                                .setNbFields(ClassMembersVisitorCounter.getNbVisitedFields())
                                .setNbMethods(ClassMembersVisitorCounter.getNbVisitedMethods())
                                .setNbAnnotations(ClassMembersVisitorCounter.getNbVisitedAnnotations())
                                .setNbDependencies(dta.getNumberOfDependenciesOfNode(g, a, v))
                                .inConflict(inConflict);
                        dependencies.add(dependency);
                    }

                    // save results to file
                    LOGGER.info("writing artifact dependencies info ");
                    CustomFileWriter.writeDependencyResults(resultsDir + "results.csv",
                            coordinates,
                            dependencies);

                    // copy dependency tree file
                    FileUtils.copyFile(new File(artifactDir + "dependencyTree.txt"), new File(resultsDir + "trees/" + coordinates + ".txt"));
                }
            }
        }
    }

    /**
     * Removes all files in the dependencies if the size of the directory is greater than a given value.
     *
     * @param dependenciesDir The directory with the dependencies
     * @param dirSize         The size threshold
     * @throws IOException
     */
    private void checkDependenciesDirSize(String dependenciesDir, BigInteger dirSize) throws IOException {
        BigInteger dependencyFolderSize = FileUtils.sizeOfAsBigInteger(new File(dependenciesDir));
        if (dependencyFolderSize.compareTo(dirSize) > 0) {
            FileUtils.cleanDirectory(new File(dependenciesDir));
        }
    }

//                MavenRepositorySystem mavenRepositorySystem = new MavenRepositorySystem();
//                Model myModel = mavenRepositorySystem.getEffectiveModel(new File(artifactDir + "pom.xml"));
//                MavenProject mavenProject = new MavenProject(myModel);
//                mavenProject.setFile(new File(artifactDir + "target/classes"));
}
