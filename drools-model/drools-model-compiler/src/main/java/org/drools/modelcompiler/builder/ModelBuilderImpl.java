/*
 * Copyright 2005 JBoss Inc
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

package org.drools.modelcompiler.builder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.drools.compiler.builder.impl.KnowledgeBuilderConfigurationImpl;
import org.drools.compiler.builder.impl.KnowledgeBuilderImpl;
import org.drools.compiler.builder.impl.TypeDeclarationFactory;
import org.drools.compiler.compiler.DialectCompiletimeRegistry;
import org.drools.compiler.compiler.PackageRegistry;
import org.drools.compiler.lang.descr.AbstractClassTypeDeclarationDescr;
import org.drools.compiler.lang.descr.CompositePackageDescr;
import org.drools.compiler.lang.descr.EnumDeclarationDescr;
import org.drools.compiler.lang.descr.GlobalDescr;
import org.drools.compiler.lang.descr.ImportDescr;
import org.drools.compiler.lang.descr.PackageDescr;
import org.drools.compiler.lang.descr.TypeDeclarationDescr;
import org.drools.core.definitions.InternalKnowledgePackage;
import org.drools.core.rule.TypeDeclaration;
import org.drools.core.util.StringUtils;
import org.drools.modelcompiler.builder.generator.DRLIdGenerator;
import org.drools.modelcompiler.builder.generator.DrlxParseUtil;
import org.drools.modelcompiler.builder.generator.declaredtype.POJOGenerator;
import org.kie.api.builder.ReleaseId;
import org.kie.internal.builder.ResultSeverity;

import static java.util.Collections.emptyList;
import static org.drools.compiler.builder.impl.ClassDefinitionFactory.createClassDefinition;
import static org.drools.modelcompiler.builder.generator.ModelGenerator.generateModel;
import static org.drools.modelcompiler.builder.generator.declaredtype.POJOGenerator.compileType;
import static org.drools.modelcompiler.builder.generator.declaredtype.POJOGenerator.registerType;

public class ModelBuilderImpl<T extends PackageSources> extends KnowledgeBuilderImpl {

    private final DRLIdGenerator exprIdGenerator = new DRLIdGenerator();

    private final Function<PackageModel, T> sourcesGenerator;
    private final Map<String, PackageModel> packageModels = new HashMap<>();
    private final ReleaseId releaseId;
    private final boolean isPattern;
    private final boolean oneClassPerRule;
    private final Collection<T> packageSources = new ArrayList<>();

    private Collection<CompositePackageDescr> compositePackages;
    private Map<String, CompositePackageDescr> compositePackagesMap;

    public ModelBuilderImpl(Function<PackageModel, T> sourcesGenerator, KnowledgeBuilderConfigurationImpl configuration, ReleaseId releaseId, boolean isPattern, boolean oneClassPerRule) {
        super(configuration);
        this.sourcesGenerator = sourcesGenerator;
        this.releaseId = releaseId;
        this.isPattern = isPattern;
        this.oneClassPerRule = oneClassPerRule;
    }

    @Override
    public void buildPackages(Collection<CompositePackageDescr> packages) {
        this.compositePackages = packages;
    }

    @Override
    public void addPackage(final PackageDescr packageDescr) {
        if (compositePackagesMap == null) {
            compositePackagesMap = new HashMap<>();
            if(compositePackages != null) {
                for (CompositePackageDescr pkg : compositePackages) {
                    compositePackagesMap.put(pkg.getNamespace(), pkg);
                }
            } else {
                compositePackagesMap.put(packageDescr.getNamespace(), new CompositePackageDescr(packageDescr.getResource(), packageDescr));
            }
            compositePackages = null;
        }

        CompositePackageDescr pkgDescr = compositePackagesMap.get(packageDescr.getNamespace());
        if (pkgDescr == null) {
            compositePackagesMap.put(packageDescr.getNamespace(), new CompositePackageDescr( packageDescr.getResource(), packageDescr) );
        } else {
            pkgDescr.addPackageDescr( packageDescr.getResource(), packageDescr );
        }

        PackageRegistry pkgRegistry = getOrCreatePackageRegistry(packageDescr);
        InternalKnowledgePackage pkg = pkgRegistry.getPackage();
        for (final ImportDescr importDescr : packageDescr.getImports()) {
            pkgRegistry.addImport(importDescr);
        }
        for (GlobalDescr globalDescr : packageDescr.getGlobals()) {
            try {
                Class<?> globalType = pkg.getTypeResolver().resolveType( globalDescr.getType() );
                addGlobal( globalDescr.getIdentifier(), globalType );
                pkg.addGlobal( globalDescr.getIdentifier(), globalType );
            } catch (ClassNotFoundException e) {
                throw new RuntimeException( e );
            }
        }
    }

    @Override
    public void postBuild() {
        Collection<CompositePackageDescr> packages = findPackages();
        initPackageRegistries(packages);
        registerTypeDeclarations( packages );
        buildDeclaredTypes( packages );
        buildOtherDeclarations(packages);
        deregisterTypeDeclarations( packages );
        buildRules(packages);
        DrlxParseUtil.clearAccessorCache();
    }

    private Collection<CompositePackageDescr> findPackages() {
        Collection<CompositePackageDescr> packages;
        if (compositePackages != null && !compositePackages.isEmpty()) {
            packages = compositePackages;
        } else if (compositePackagesMap != null) {
            packages = compositePackagesMap.values();
        } else {
            packages = emptyList();
        }
        return packages;
    }

    @Override
    protected void initPackageRegistries(Collection<CompositePackageDescr> packages) {
        for ( CompositePackageDescr packageDescr : packages ) {
            if ( StringUtils.isEmpty(packageDescr.getName()) ) {
                packageDescr.setName( getBuilderConfiguration().getDefaultPackageName() );
            }

            PackageRegistry pkgRegistry = getPackageRegistry( packageDescr.getNamespace() );
            if (pkgRegistry == null) {
                getOrCreatePackageRegistry( packageDescr );
            } else {
                for (ImportDescr importDescr : packageDescr.getImports()) {
                    pkgRegistry.registerImport(importDescr.getTarget());
                }
            }
        }
    }

    private void registerTypeDeclarations( Collection<CompositePackageDescr> packages ) {
        for (CompositePackageDescr packageDescr : packages) {
            InternalKnowledgePackage pkg = getOrCreatePackageRegistry(packageDescr).getPackage();
            for (TypeDeclarationDescr typeDescr : packageDescr.getTypeDeclarations()) {
                processTypeDeclarationDescr(pkg, typeDescr);
            }
            for (EnumDeclarationDescr enumDeclarationDescr : packageDescr.getEnumDeclarations()) {
                processTypeDeclarationDescr(pkg, enumDeclarationDescr);
            }
        }
    }

    private void processTypeDeclarationDescr(InternalKnowledgePackage pkg, AbstractClassTypeDeclarationDescr typeDescr) {
        normalizeAnnotations(typeDescr, pkg.getTypeResolver(), false);
        try {
            Class<?> typeClass = pkg.getTypeResolver().resolveType( typeDescr.getTypeName() );
            String typePkg = typeClass.getPackage().getName();
            String typeName = typeClass.getName().substring( typePkg.length() + 1 );
            TypeDeclaration type = new TypeDeclaration(typeName );
            type.setTypeClass( typeClass );
            type.setResource( typeDescr.getResource() );
            type.setTypeClassDef( createClassDefinition( typeClass, typeDescr.getResource() ) );
            TypeDeclarationFactory.processAnnotations(typeDescr, type);
            getOrCreatePackageRegistry(new PackageDescr(typePkg)).getPackage().addTypeDeclaration(type );
        } catch (ClassNotFoundException e) {
            TypeDeclaration type = new TypeDeclaration( typeDescr.getTypeName() );
            type.setResource( typeDescr.getResource() );
            TypeDeclarationFactory.processAnnotations(typeDescr, type);
            pkg.addTypeDeclaration( type );
        }
    }

    private void deregisterTypeDeclarations( Collection<CompositePackageDescr> packages ) {
        for (CompositePackageDescr packageDescr : packages) {
            getOrCreatePackageRegistry(packageDescr).getPackage().getTypeDeclarations().clear();
        }
    }

    protected void buildRules(Collection<CompositePackageDescr> packages) {
        if (hasErrors()) { // if Error while generating pojo do not try compile rule as they very likely depends hence fail too.
            return;
        }

        for (CompositePackageDescr packageDescr : packages) {
            setAssetFilter(packageDescr.getFilter());
            PackageRegistry pkgRegistry = getPackageRegistry(packageDescr.getNamespace());
            compileKnowledgePackages(packageDescr, pkgRegistry);
            setAssetFilter(null);

            PackageModel pkgModel = packageModels.remove( pkgRegistry.getPackage().getName() );
            pkgModel.setOneClassPerRule( oneClassPerRule );
            if (getResults( ResultSeverity.ERROR ).isEmpty()) {
                packageSources.add( sourcesGenerator.apply( pkgModel ) );
            }
        }
    }

    private void buildDeclaredTypes( Collection<CompositePackageDescr> packages ) {
        for (CompositePackageDescr packageDescr : packages) {
            PackageRegistry pkgRegistry = getPackageRegistry(packageDescr.getNamespace());
            generatePOJOs(packageDescr, pkgRegistry);
        }

        List<GeneratedClassWithPackage> allGeneratedPojos =
                packageModels.values().stream()
                             .flatMap(p -> p.getGeneratedPOJOsSource().stream().map(c -> new GeneratedClassWithPackage(c, p.getName(), p.getImports(), p.getStaticImports())))
                        .collect( Collectors.toList());


        // Every class gets compiled in each classloader, maybe they can be compiled only one time?
        final Map<String, Class<?>> allCompiledClasses = new HashMap<>();
        for (CompositePackageDescr packageDescr : packages) {
            InternalKnowledgePackage pkg = getPackageRegistry(packageDescr.getNamespace()).getPackage();
            allCompiledClasses.putAll(compileType(this, pkg.getPackageClassLoader(), allGeneratedPojos));
        }

        for (CompositePackageDescr packageDescr : packages) {
            InternalKnowledgePackage pkg = getPackageRegistry(packageDescr.getNamespace()).getPackage();
            allGeneratedPojos.forEach(c -> registerType(pkg.getTypeResolver(), allCompiledClasses));
        }
    }

    protected void generatePOJOs(PackageDescr packageDescr, PackageRegistry pkgRegistry) {
        InternalKnowledgePackage pkg = pkgRegistry.getPackage();
        String pkgName = pkg.getName();
        PackageModel model = packageModels.computeIfAbsent(pkgName, s -> {
            final DialectCompiletimeRegistry dialectCompiletimeRegistry = pkgRegistry.getDialectCompiletimeRegistry();
            return new PackageModel(releaseId, pkgName, this.getBuilderConfiguration(), isPattern, dialectCompiletimeRegistry, exprIdGenerator);
        });
        model.addImports(pkg.getTypeResolver().getImports());
        new POJOGenerator(this, pkg, packageDescr, model).findPOJOorGenerate();
    }

    @Override
    protected void compileKnowledgePackages(PackageDescr packageDescr, PackageRegistry pkgRegistry) {
        validateUniqueRuleNames(packageDescr);
        InternalKnowledgePackage pkg = pkgRegistry.getPackage();
        String pkgName = pkg.getName();
        PackageModel model = packageModels.computeIfAbsent(pkgName, s -> {
            final DialectCompiletimeRegistry dialectCompiletimeRegistry = pkgRegistry.getDialectCompiletimeRegistry();
            return new PackageModel(releaseId, pkgName, this.getBuilderConfiguration(), isPattern, dialectCompiletimeRegistry, exprIdGenerator);
        });
        generateModel(this, pkg, packageDescr, model, isPattern);
    }

    public Collection<T> getPackageSources() {
        return packageSources;
    }
}
