package org.apache.maven.plugin.deploy;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.deployer.ArtifactDeploymentException;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/**
 * Deploys an artifact to remote repository.
 *
 * @author <a href="mailto:evenisse@apache.org">Emmanuel Venisse</a>
 * @author <a href="mailto:jdcasey@apache.org">John Casey (refactoring only)</a>
 * @version $Id: DeployMojo.java 1620080 2014-08-23 21:34:57Z khmarbaise $
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY, threadSafe = true)
public class DeployMojo extends AbstractDeployMojo {

    private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern
            .compile("(.+)::(.+)::(.+)");

    /**
     * When building with multiple threads, reaching the last project doesn't
     * have to mean that all projects are ready to be deployed
     */
    private static final AtomicInteger readyProjectsCounter = new AtomicInteger();

    private static final List<DeployRequest> deployRequests = Collections
            .synchronizedList(new ArrayList<DeployRequest>());

    /**
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    private List<MavenProject> reactorProjects;

    /**
     * Whether every project should be deployed during its own deploy-phase or
     * at the end of the multimodule build. If set to {@code true} and the build
     * fails, none of the reactor projects is deployed.
     * <strong>(experimental)</strong>
     *
     * @since 2.8
     */
    @Parameter(defaultValue = "false", property = "deployAtEnd")
    private boolean deployAtEnd;

    /**
     * @deprecated either use project.getArtifact() or
     * reactorProjects.get(i).getArtifact()
     */
    @Parameter(defaultValue = "${project.artifact}", required = true, readonly = true)
    private Artifact artifact;

    /**
     * @deprecated either use project.getPackaging() or
     * reactorProjects.get(i).getPackaging()
     */
    @Parameter(defaultValue = "${project.packaging}", required = true, readonly = true)
    private String packaging;

    /**
     * @deprecated either use project.getFile() or
     * reactorProjects.get(i).getFile()
     */
    @Parameter(defaultValue = "${project.file}", required = true, readonly = true)
    private File pomFile;

    /**
     * Specifies an alternative repository to which the project artifacts should
     * be deployed ( other than those specified in
     * &lt;distributionManagement&gt; ). <br/>
     * Format: id::layout::url
     * <dl>
     * <dt>id</dt>
     * <dd>The id can be used to pick up the correct credentials from the
     * settings.xml</dd>
     * <dt>layout</dt>
     * <dd>Either <code>default</code> for the Maven2 layout or
     * <code>legacy</code> for the Maven1 layout. Maven3 also uses the
     * <code>default</code> layout.</dd>
     * <dt>url</dt>
     * <dd>The location of the repository</dd>
     * </dl>
     */
    @Parameter(property = "altDeploymentRepository")
    private String altDeploymentRepository;

    /**
     * The alternative repository to use when the project has a snapshot
     * version.
     *
     * @see DeployMojo#altDeploymentRepository
     * @since 2.8
     */
    @Parameter(property = "altSnapshotDeploymentRepository")
    private String altSnapshotDeploymentRepository;

    /**
     * The alternative repository to use when the project has a final version.
     *
     * @see DeployMojo#altDeploymentRepository
     * @since 2.8
     */
    @Parameter(property = "altReleaseDeploymentRepository")
    private String altReleaseDeploymentRepository;

    /**
     * @deprecated either use project.getAttachedArtifacts() or
     * reactorProjects.get(i).getAttachedArtifacts()
     */
    @Parameter(defaultValue = "${project.attachedArtifacts}", required = true, readonly = true)
    private List attachedArtifacts;

    /**
     * Set this to 'true' to bypass artifact deploy
     *
     * @since 2.4
     */
    @Parameter(property = "maven.deploy.skip", defaultValue = "false")
    private boolean skip;

    public void execute() throws MojoExecutionException, MojoFailureException {
        boolean addedDeployRequest = false;

        boolean isFacade = (project.getArtifactId().contains("facade") && project.hasParent())
                || ("pom".equals(project.getPackaging()) && !project.hasParent());
        if (skip) {
            getLog().info("Skipping artifact deployment");
        } else if (isFacade) {
            failIfOffline();
            DeployRequest currentExecutionDeployRequest = new DeployRequest().setProject(project)
                    .setUpdateReleaseInfo(isUpdateReleaseInfo())
                    .setRetryFailedDeploymentCount(getRetryFailedDeploymentCount())
                    .setAltReleaseDeploymentRepository(altReleaseDeploymentRepository)
                    .setAltSnapshotDeploymentRepository(altSnapshotDeploymentRepository)
                    .setAltDeploymentRepository(altDeploymentRepository);
            if (!deployAtEnd) {
                deployProject(currentExecutionDeployRequest);
            } else {
                deployRequests.add(currentExecutionDeployRequest);
                addedDeployRequest = true;
            }
        } else {
            getLog().info("略过非facade包");
        }

        boolean projectsReady = readyProjectsCounter.incrementAndGet() == reactorProjects.size();
        if (projectsReady) {
            synchronized (deployRequests) {
                while (!deployRequests.isEmpty()) {
                    deployProject(deployRequests.remove(0));
                }
            }
        } else if (addedDeployRequest) {
            getLog().info(
                    "Deploying " + project.getGroupId() + ":" + project.getArtifactId() + ":"
                            + project.getVersion() + " at end");
        }
    }

    private void deployProject(DeployRequest request) throws MojoExecutionException,
            MojoFailureException {

        Artifact artifact = request.getProject().getArtifact();
        String packaging = request.getProject().getPackaging();
        File pomFile;
        if ("jar".equals(request.getProject().getPackaging())) {
            pomFile = generatePomFile(request.getProject());
        } else {
            pomFile = request.getProject().getFile();
        }

        @SuppressWarnings("unchecked")
        List<Artifact> attachedArtifacts = request.getProject().getAttachedArtifacts();

        ArtifactRepository repo = getDeploymentRepository(request.getProject(),
                request.getAltDeploymentRepository(), request.getAltReleaseDeploymentRepository(),
                request.getAltSnapshotDeploymentRepository());

        String protocol = repo.getProtocol();

        if (protocol.equalsIgnoreCase("scp")) {
            File sshFile = new File(System.getProperty("user.home"), ".ssh");

            if (!sshFile.exists()) {
                sshFile.mkdirs();
            }
        }

        // Deploy the POM
        boolean isPomArtifact = "pom".equals(packaging);
        if (!isPomArtifact) {
            ArtifactMetadata metadata = new ProjectArtifactMetadata(artifact, pomFile);
            Collection<ArtifactMetadata> metadataList = artifact.getMetadataList();
            Iterator<ArtifactMetadata> iterator = metadataList.iterator();
            while (iterator.hasNext()) {
                ArtifactMetadata next = iterator.next();
                if (next.getKey().equals(metadata.getKey())) {
                    iterator.remove();
                }
            }
            artifact.addMetadata(metadata);
        }

        if (request.isUpdateReleaseInfo()) {
            artifact.setRelease(true);
        }

        int retryFailedDeploymentCount = request.getRetryFailedDeploymentCount();
        try {
            if (isPomArtifact) {
                deploy(pomFile, artifact, repo, getLocalRepository(), retryFailedDeploymentCount);
            } else {
                File file = artifact.getFile();

                if (file != null && file.isFile()) {
                    deploy(file, artifact, repo, getLocalRepository(), retryFailedDeploymentCount);
                } else if (!attachedArtifacts.isEmpty()) {
                    getLog().info(
                            "No primary artifact to deploy, deploying attached artifacts instead.");

                    Artifact pomArtifact = artifactFactory.createProjectArtifact(
                            artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion());
                    pomArtifact.setFile(pomFile);
                    if (request.isUpdateReleaseInfo()) {
                        pomArtifact.setRelease(true);
                    }

                    deploy(pomFile, pomArtifact, repo, getLocalRepository(),
                            retryFailedDeploymentCount);

                    // propagate the timestamped version to the main artifact
                    // for the attached artifacts to pick it up
                    artifact.setResolvedVersion(pomArtifact.getVersion());
                } else {
                    String message = "The packaging for this project did not assign a file to the build artifact";
                    throw new MojoExecutionException(message);
                }

                for (Artifact attached : attachedArtifacts) {
                    deploy(attached.getFile(), attached, repo, getLocalRepository(), retryFailedDeploymentCount);
                }
            }
        } catch (ArtifactDeploymentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            StringBuilder builder = new StringBuilder();
            builder.append(getLocalRepository().getBasedir());
            builder.append("/");
            builder.append(artifact.getGroupId().replaceAll("[.]", "/"));
            builder.append("/");
            builder.append(artifact.getArtifactId());
            builder.append("/");
            builder.append(artifact.getBaseVersion());

            getLog().info("清空本地 intall 的目录." + builder.toString());
            try {
                FileUtils.cleanDirectory(new File(builder.toString()));
            } catch (IOException e) {
                getLog().warn(e.getMessage());
            }
        }
    }

    ArtifactRepository getDeploymentRepository(MavenProject project,
                                               String altDeploymentRepository,
                                               String altReleaseDeploymentRepository,
                                               String altSnapshotDeploymentRepository)
            throws MojoExecutionException,
            MojoFailureException {
        ArtifactRepository repo = null;

        String altDeploymentRepo;
        if (ArtifactUtils.isSnapshot(project.getVersion())
                && altSnapshotDeploymentRepository != null) {
            altDeploymentRepo = altSnapshotDeploymentRepository;
        } else if (!ArtifactUtils.isSnapshot(project.getVersion())
                && altReleaseDeploymentRepository != null) {
            altDeploymentRepo = altReleaseDeploymentRepository;
        } else {
            altDeploymentRepo = altDeploymentRepository;
        }

        if (altDeploymentRepo != null) {
            getLog().info("Using alternate deployment repository " + altDeploymentRepo);

            Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher(altDeploymentRepo);

            if (!matcher.matches()) {
                throw new MojoFailureException(altDeploymentRepo, "Invalid syntax for repository.",
                        "Invalid syntax for alternative repository. Use \"id::layout::url\".");
            } else {
                String id = matcher.group(1).trim();
                String layout = matcher.group(2).trim();
                String url = matcher.group(3).trim();

                ArtifactRepositoryLayout repoLayout = getLayout(layout);

                repo = repositoryFactory.createDeploymentArtifactRepository(id, url, repoLayout,
                        true);
            }
        }

        if (repo == null) {
            repo = project.getDistributionManagementArtifactRepository();
        }

        if (repo == null) {
            String msg = "Deployment failed: repository element was not specified in the POM inside"
                    + " distributionManagement element or in -DaltDeploymentRepository=id::layout::url parameter";

            throw new MojoExecutionException(msg);
        }

        return repo;
    }

    /**
     * Generates a minimal POM from the user-supplied artifact information.
     *
     * @return The path to the generated POM file, never <code>null</code>.
     * @throws MojoExecutionException If the generation failed.
     */
    private File generatePomFile(MavenProject project) throws MojoExecutionException {
        Model model = generateModel(project);

        Writer fw = null;
        try {
            File tempFile = File.createTempFile("mvndeploy", ".pom");
            tempFile.deleteOnExit();

            fw = WriterFactory.newXmlWriter(tempFile);
            new MavenXpp3Writer().write(fw, model);

            return tempFile;
        } catch (IOException e) {
            throw new MojoExecutionException("Error writing temporary pom file: " + e.getMessage(),
                    e);
        } finally {
            IOUtil.close(fw);
        }
    }

    /**
     * Generates a minimal model from the user-supplied artifact information.
     *
     * @return The generated model, never <code>null</code>.
     */
    @SuppressWarnings("unchecked")
    private Model generateModel(MavenProject project) {
        Model model = new Model();
        model.setModelVersion(project.getModelVersion());
        model.setGroupId(project.getGroupId());
        model.setArtifactId(project.getArtifactId());
        model.setVersion(project.getVersion());
        model.setPackaging(project.getPackaging());
        model.setDescription(project.getDescription());
        model.setPackaging(project.getPackaging());
        model.setDistributionManagement(project.getDistributionManagement());
        model.setProperties(project.getProperties());
        model.setPluginRepositories(project.getPluginRepositories());
        return model;
    }

}
