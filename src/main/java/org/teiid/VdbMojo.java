/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.teiid;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.teiid.adminapi.DataPolicy;
import org.teiid.adminapi.impl.DataPolicyMetadata;
import org.teiid.adminapi.impl.VDBImportMetadata;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.adminapi.impl.VDBMetadataParser;

/**
 * https://stackoverflow.com/questions/1427722/how-do-i-create-a-new-packaging-type-for-maven
 * http://softwaredistilled.blogspot.com/2015/07/how-to-create-custom-maven-packaging.html
 */
@Mojo(name = "vdb")
public class VdbMojo extends AbstractMojo {
    private static final String SLASH = "/";

    @Parameter( defaultValue = "${basedir}/src/main/vdb/META-INF/vdb.xml" )
    private String vdbXmlFile;

    @Parameter( defaultValue = "${basedir}/src/main/vdb" )
    private String vdbFolder;

    @Component
    private MavenProject project;

    @Parameter(property = "project.build.directory", readonly = true)
    private String outputDirectory;

    @Parameter(property = "project.build.finalName", readonly = true)
    private String finalName;

    
     //A list of folders or files to be included in the final artifact archive.
    @Parameter
    private File[] includes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        File artifact = new File(this.outputDirectory, this.finalName+".vdb");

        this.project.getArtifact().setFile(artifact);

        try (ArchiveOutputStream archive = this.getStream(artifact)) {
            File vdb = this.getVDBFile();
            if (vdb == null) {
                throw new MojoExecutionException("No VDB File found in directory" + this.vdbFolder);
            }

            // add config, classes, lib and META-INF directories
            File f = new File(this.vdbFolder);
            Set<File> directories = new LinkedHashSet<>();
            gatherContents(f, directories);

            // do not allow vdb-import in the case of VDB represented with .ddl
            if (vdb.getName().endsWith("-vdb.ddl")) {
            	addFile(archive, "META-INF/vdb.ddl", vdb);
            	return;
            }

            // check if the VDB has any vdb imports, if yes, then check the dependencies
            VDBMetaData top = VDBMetadataParser.unmarshell(new FileInputStream(vdb));
            if (!top.getVDBImports().isEmpty()) {
                // read import vdbs
                Set<Artifact> dependencies = project.getDependencyArtifacts();
                for (Artifact d : dependencies) {

                    if (!d.getFile().getName().endsWith(".vdb")) {
                        continue;
                    }

                    File vdbDir = unzipContents(d);
                    File childFile = new File(vdbDir, "META-INF/vdb.xml");
                    System.out.println("Merging VDB " + childFile.getCanonicalPath());
                    VDBMetaData child = VDBMetadataParser.unmarshell(new FileInputStream(childFile));

                    if (!child.getVDBImports().isEmpty()) {
                        throw new MojoExecutionException("Nested VDB imports are not supported" + d.getArtifactId());
                    }

                    VDBImportMetadata matched = null;
                    for (VDBImportMetadata importee : top.getVDBImports()) {
                        if (child.getName().equals(importee.getName())
                                && child.getVersion().equals(importee.getVersion())) {

                            gatherContents(vdbDir, directories);

                            top.getVisibilityOverrides().putAll(child.getVisibilityOverrides());
							child.getModelMetaDatas().forEach((k, v) -> {
								top.addModel(v);
								String visibilityOverride = top.getPropertyValue(v.getName() + ".visible"); //$NON-NLS-1$
								if (visibilityOverride != null) {
									boolean visible = Boolean.valueOf(visibilityOverride);
									top.setVisibilityOverride(v.getName(), visible);
								}
							});

                            child.getOverrideTranslatorsMap().forEach((k,v) -> top.addOverideTranslator(v));

                			if (importee.isImportDataPolicies()) {
                				for (DataPolicy dp : child.getDataPolicies()) {
                					DataPolicyMetadata role = (DataPolicyMetadata)dp;
                					if (top.addDataPolicy(role) != null) {
										throw new MojoExecutionException(top.getName() + "." + top.getVersion()
												+ " imports a conflicting role " + role.getName() + " from "
												+ child.getName() + "." + child.getVersion());
                					}
                					if (role.isGrantAll()) {
                						role.setSchemas(child.getModelMetaDatas().keySet());
                					}
                				}
                			}
                            matched = importee;
                            break;
                        }
                    }
                    if (matched != null) {
                        top.getVDBImports().remove(matched);
                    }
                }
            }
            add(archive, "", directories.toArray(new File[directories.size()]));

            File finalVDB = new File("target", "vdb.xml");
            VDBMetadataParser.marshell(top, new FileOutputStream(finalVDB));
            addFile(archive, "META-INF/vdb.xml", finalVDB);

        } catch (Exception e) {
            throw new MojoExecutionException("Exception when creating artifact archive.", e);
        }
    }

    private void gatherContents(File f, Set<File> directories) {
        if (f.exists() && f.isDirectory()) {
            File[] list = f.listFiles();

            for (File l : list) {
                if (l.isDirectory()) {
                    directories.add(l);
                }
                if (!l.getName().endsWith("vdb.xml")) {
                    directories.add(l);
                }
            }
        }
    }

    private File unzipContents(Artifact d) throws FileNotFoundException, IOException {
        File f = new File("target", d.getArtifactId());
        f.mkdirs();
        System.out.println("unzipping " + d.getArtifactId() + " to directory "+ f.getCanonicalPath());

        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(new FileInputStream(d.getFile()));
        ZipEntry ze = zis.getNextEntry();
        while (ze != null) {
            String fileName = ze.getName();
            System.out.println("\t" + fileName);
            File newFile = new File(f, fileName);
            new File(newFile.getParent()).mkdirs();
            FileOutputStream fos = new FileOutputStream(newFile);
            int len;
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fos.close();

            zis.closeEntry();
            ze = zis.getNextEntry();
        }
        zis.close();
        return f;
    }

    private File getVDBFile() {
        File f= new File(this.vdbXmlFile);
        if (f.exists()) {
            System.out.println("Found VDB = " + this.vdbXmlFile);
            return f;
        } else {
            f = new File(this.vdbFolder);
            if (f.exists() && f.isDirectory()) {
                File[] list = f.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.endsWith("-vdb.xml") || name.endsWith("-vdb.ddl");
                    }
                });
                if (list.length != 0) {
                    System.out.println("Found VDB = " + list[0].getName());
                    return list[0];
                }
            }
        }
        return null;
    }

    private void addFile(ArchiveOutputStream archive, String name, File file) throws IOException {
        System.out.println("Adding file = " + name +" from " + file.getCanonicalPath());
        ArchiveEntry entry = this.entry(file, name);
        archive.putArchiveEntry(entry);
        IOUtils.copy(new FileInputStream(file), archive);
        archive.closeArchiveEntry();
    }

    private void add(ArchiveOutputStream archive, String path, File... files) throws IOException {
        for (File file : files) {
            if (!file.exists()) {
                throw new FileNotFoundException("Folder or file not found: " + file.getPath());
            }
            String name = path + file.getName();
            if (file.isDirectory()) {
                this.add(archive, name + SLASH, file.listFiles());
            } else {
                if (!name.endsWith("vdb.xml")) {
                    addFile(archive, name, file);
                }
            }
        }
    }

    protected ArchiveOutputStream getStream(File artifact) throws IOException {
        File outdir = new File(outputDirectory);
        if (!outdir.exists()) {
            outdir.mkdirs();
        }
        FileOutputStream output = new FileOutputStream(artifact);
        try {
            return new ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.ZIP, output);
        } catch (ArchiveException e) {
            throw new IOException(e);
        }
    }

    protected ArchiveEntry entry(File file, String name) {
        ZipArchiveEntry entry = new ZipArchiveEntry(file, name);
        return entry;
    }
}