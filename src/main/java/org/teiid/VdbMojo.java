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

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * @see https://stackoverflow.com/questions/1427722/how-do-i-create-a-new-packaging-type-for-maven
 * @see http://softwaredistilled.blogspot.com/2015/07/how-to-create-custom-maven-packaging.html
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

    /**
     * A list of folders or files to be included in the final artifact archive.
     */
    @Parameter
    private File[] includes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        File artifact = new File(this.outputDirectory, this.finalName+".vdb");

        this.project.getArtifact().setFile(artifact);

        try (ArchiveOutputStream archive = this.getStream(artifact)) {
            File vdb = this.getVDBFile();
            if (vdb != null) {
                this.addFile(archive, "META-INF/vdb.xml", vdb);
            } else {
                throw new MojoExecutionException("No VDB File found in directory" + this.vdbFolder);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Exception when creating artifact archive.", e);
        }
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
                        return name.endsWith("-vdb.xml");
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
        System.out.println("Adding file = " + name);
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
                ArchiveEntry entry = this.entry(file, name);
                archive.putArchiveEntry(entry);
                IOUtils.copy(new FileInputStream(file), archive);
                archive.closeArchiveEntry();
            }
        }
    }

    protected ArchiveOutputStream getStream(File artifact) throws IOException {
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