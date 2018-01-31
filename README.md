# vdb-maven-plugin

A Maven PlugIn to build a VDB file. WildFly Swarm only allows ZIP archive based artifact deployment when a VDB needs to be deployed with WildFly Swarm Teiid. This Maven plugin has packaging type of "vdb" can be defined in a pom.xml as

````
<modelVersion>4.0.0</modelVersion>
<groupId>com.example</groupId>
<artifactId>teiid-demo</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>vdb</packaging>

<build>
  <plugins>
    <plugin>
      <groupId>org.teiid</groupId>
      <artifactId>vdb-maven-plugin</artifactId>
      <version>1.0-SNAPSHOT</version>
      <extensions>true</extensions>
      <executions>
        <execution>
          <id>test</id>
          <goals>
            <goal>vdb</goal>
          </goals>
          <configuration>
            <!-- your configuration here -->
            <!-- 
            <vdbXmlFile>path/to/vdbfile</vdbXmlFile> <!-- optional -->
            <vdbFolder>path/to/vdbfile</vdbFolder> <!-- optional -->
            -->
          </configuration>          
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
````
The default `vdbXMLFile` is set to "${basedir}/src/main/vdb/META-INF/vdb.xml", if this file is avaialable, then it this file is used as the VDB file. If this file is not there then code will look into `vdbFolder` directory and scan for `-vdb.xml` files in this folder. Default `vdbFolder` value is `${basedir}/src/main/vdb`.

When it finds the -vdb.xml file, it will wrap it in Zip archive with .vdb extension and marks it as artifact of the build process.
