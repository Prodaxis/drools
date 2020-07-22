/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.kie.scanner;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.drools.compiler.kie.builder.impl.InternalKieModule;
import org.eclipse.aether.artifact.Artifact;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.ReleaseId;
import org.kie.internal.utils.ClassLoaderResolver;
import org.appformer.maven.integration.ArtifactResolver;
import org.appformer.maven.support.DependencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MavenClassLoaderResolver implements ClassLoaderResolver {
    private static final ProtectionDomain  PROTECTION_DOMAIN;
    
    private static final Logger logger = LoggerFactory.getLogger(MavenClassLoaderResolver.class);

    static {
        PROTECTION_DOMAIN = (ProtectionDomain) AccessController.doPrivileged( new PrivilegedAction() {

            public Object run() {
                return MavenClassLoaderResolver.class.getProtectionDomain();
            }
        } );
    }
    

    @Override
    public ClassLoader getClassLoader(KieModule kmodule) {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null) {
            parent = ClassLoader.getSystemClassLoader();
        }
        if (parent == null) {
            parent = MavenClassLoaderResolver.class.getClassLoader();
        }

        InternalKieModule internalKModule = (InternalKieModule)kmodule;
        Collection<ReleaseId> jarDependencies = internalKModule.getJarDependencies( DependencyFilter.COMPILE_FILTER );
        if (jarDependencies.isEmpty()) {
            return parent;
        }

        ArtifactResolver resolver = ArtifactResolver.getResolverFor( internalKModule.getPomModel() );
        List<URL> urls = new ArrayList<URL>();
        List<ReleaseId> unresolvedDeps = new ArrayList<ReleaseId>();

        for (ReleaseId rid : jarDependencies) {
            try {
                Artifact artifact = resolver.resolveArtifact(rid);
                if( artifact != null ) {
                    File jar = artifact.getFile(); 
                    urls.add( jar.toURI().toURL() );
                } else {
                    logger.error( "Dependency artifact not found for: " + rid );
                    unresolvedDeps.add(rid);
                }
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        try {
        	ArrayList<File> parteorFiles = getAllParteorFiles();
            if(null != parteorFiles){
            	for (File file : parteorFiles) {
                	urls.add( file.toURI().toURL() );
                }
            }	
        }catch (Exception e) {
            
        }
        
        internalKModule.setUnresolvedDependencies(unresolvedDeps);
        return new KieURLClassLoader(urls.toArray(new URL[urls.size()]), parent);
    }
    
    public static String getInstallLocation() {
		return System.getProperty("jboss.home.dir") + File.separator + "modules" + File.separator + "com" + File.separator + "prodaxis" + File.separator + "bpm" + File.separator + "main";
	}
    
    public ArrayList<File> getAllParteorFiles(){
    	String startingPoint = getInstallLocation();
        String findPattern = "*.jar";
        Path startingDir = Paths.get(startingPoint);
        Finder theFinder = new Finder(findPattern);
        try {
			Files.walkFileTree(startingDir, theFinder);
			return theFinder.myFileArray;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
    }
    
    public static class Finder extends SimpleFileVisitor<Path>{
        private final PathMatcher theMatcher;
        ArrayList<File> myFileArray = new ArrayList<File>();

        Finder(String pattern) {
            theMatcher = FileSystems.getDefault().getPathMatcher("glob:"+pattern);
        }

        void find (Path file){
            Path name = file.getFileName();
            if (name != null && theMatcher.matches(name)){
                myFileArray.add(file.toFile());
            }
        }

        File[] returnFileArray(){
            File[] x = new File[myFileArray.size()];
            return myFileArray.toArray(x);
        }

        @Override
        public FileVisitResult visitFile (Path file,BasicFileAttributes attrs){
            find(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory (Path dir, BasicFileAttributes attrs){
            find(dir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed (Path file, IOException exc) {
            System.err.println(exc);
            return FileVisitResult.CONTINUE;
        }
    }

}
