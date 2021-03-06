/*
 * Copyright (c) 2010-2012 Engine Yard, Inc.
 * Copyright (c) 2007-2009 Sun Microsystems, Inc.
 * This source code is available under the MIT license.
 * See the file LICENSE.txt for details.
 */

package org.jruby.rack;

import org.jruby.Ruby;
import org.jruby.RubyInstanceConfig;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.JavaUtil;
import org.jruby.rack.servlet.ServletRackContext;
import org.jruby.rack.servlet.RewindableInputStream;
import org.jruby.rack.util.IOHelpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import java.io.IOException;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Iterator;
import java.util.Set;

/**
 * Default application factory creates a new application instance on each
 * {@link #getApplication()} invocation. It does not manage applications it
 * creates (except for the error application that is assumed to be shared).
 * 
 * @see SharedRackApplicationFactory
 * @see PoolingRackApplicationFactory
 * 
 * @author nicksieger
 */
public class DefaultRackApplicationFactory implements RackApplicationFactory {
    
    private String rackupScript, rackupLocation;
    private ServletRackContext rackContext;
    private RubyInstanceConfig runtimeConfig;
    private RackApplication errorApplication;

    /**
     * Convenience helper for unwrapping a {@link RackApplicationFactoryDecorator}.
     * @param factory the (likely decorated) factory
     * @return unwrapped "real" factory (might be the same as given)
     */
    public static RackApplicationFactory getRealFactory(final RackApplicationFactory factory) {
        if ( factory instanceof RackApplicationFactory.Decorator ) {
            return getRealFactory( ((Decorator) factory).getDelegate() );
        }
        return factory;
    }
    
    public RackContext getRackContext() {
        return rackContext;
    }
    
    public String getRackupScript() {
        return rackupScript;
    }
    
    /**
     * Initialize this factory using the given context.
     * <br/>
     * NOTE: exception handling is left to the outer factory.
     * @param rackContext 
     */
    public void init(final RackContext rackContext) {
        // NOTE: this factory is not supposed to be directly exposed
        // thus does not wrap exceptions into RackExceptions here ...
        // same applies for #newApplication() and #getApplication()
        this.rackContext = (ServletRackContext) rackContext;
        resolveRackupScript();
        this.runtimeConfig = createRuntimeConfig();
        rackContext.log(RackLogger.INFO, runtimeConfig.getVersionString());
        configureDefaults();
    }

    /**
     * Creates a new application instance (without initializing it).
     * <br/>
     * NOTE: exception handling is left to the outer factory.
     * @return new application instance
     */
    public RackApplication newApplication() {
        return createApplication(new ApplicationObjectFactory() {
            public IRubyObject create(Ruby runtime) {
                return createApplicationObject(runtime);
            }
        });
    }

    /**
     * Creates a new application and initializes it.
     * <br/>
     * NOTE: exception handling is left to the outer factory.
     * @return new, initialized application
     */
    public RackApplication getApplication() {
        final RackApplication app = newApplication();
        app.init();
        return app;
    }

    /**
     * Destroys the application (assumably) created by this factory.
     * <br/>
     * NOTE: exception handling is left to the outer factory.
     * @param app the application to "release"
     */
    public void finishedWithApplication(final RackApplication app) {
        if ( app != null ) app.destroy();
    }

    /**
     * @return the (default) error application
     */
    public RackApplication getErrorApplication() {
        if (errorApplication == null) {
            synchronized(this) {
                if (errorApplication == null) {
                    errorApplication = newErrorApplication();
                }
            }
        }
        return errorApplication;
    }

    /** 
     * Set the (default) error application to be used.
     * @param errorApplication
     */
    public synchronized void setErrorApplication(RackApplication errorApplication) {
        this.errorApplication = errorApplication;
    }
    
    public void destroy() {
        if (errorApplication != null) {
            synchronized(this) {
                if (errorApplication != null) {
                    errorApplication.destroy();
                    errorApplication = null;
                }
            }
        }
    }

    public IRubyObject createApplicationObject(final Ruby runtime) {
        if (rackupScript == null) {
            rackContext.log(RackLogger.WARN, "no rackup script found - starting empty Rack application!");
            rackupScript = "";
        }
        runtime.evalScriptlet("load 'jruby/rack/boot/rack.rb'");
        return createRackServletWrapper(runtime, rackupScript, rackupLocation);
    }

    public IRubyObject createErrorApplicationObject(final Ruby runtime) {
        String errorApp = rackContext.getConfig().getProperty("jruby.rack.error.app");
        String errorAppPath = "<web.xml>";
        if (errorApp == null) {
            errorApp = rackContext.getConfig().getProperty("jruby.rack.error.app.path");
            if (errorApp != null) {
                errorAppPath = rackContext.getRealPath(errorApp);
                try {
                    errorApp = IOHelpers.inputStreamToString(rackContext.getResourceAsStream(errorApp));
                }
                catch (IOException e) {
                    rackContext.log(RackLogger.WARN,
                        "failed to read jruby.rack.error.app.path = '" + errorApp + "' " +
                        "will use default error application", e);
                    errorApp = errorAppPath = null;
                }
            }
            
        }
        if (errorApp == null) {
            errorApp = "require 'jruby/rack/error_app' \n" +
            "use Rack::ShowStatus \n" +
            "run JRuby::Rack::ErrorApp.new";
        }
        runtime.evalScriptlet("load 'jruby/rack/boot/rack.rb'");
        return createRackServletWrapper(runtime, errorApp, errorAppPath);
    }

    public RackApplication newErrorApplication() {
        Boolean error = rackContext.getConfig().getBooleanProperty("jruby.rack.error");
        if ( error != null && ! error.booleanValue() ) { // jruby.rack.error = false
            return new DefaultErrorApplication(rackContext);
        }
        try {
            RackApplication app = createErrorApplication(
                new ApplicationObjectFactory() {
                    public IRubyObject create(Ruby runtime) {
                        return createErrorApplicationObject(runtime);
                    }
                }
            );
            app.init();
            return app;
        }
        catch (Exception e) {
            rackContext.log(RackLogger.WARN, "error application could not be initialized", e);
            return new DefaultErrorApplication(rackContext); // backwards compatibility
        }
    }

    /**
     * @see #createRackServletWrapper(Ruby, String, String) 
     * @param runtime
     * @param rackup
     * @return (Ruby) built Rack Servlet handler
     */
    protected IRubyObject createRackServletWrapper(Ruby runtime, String rackup) {
        return createRackServletWrapper(runtime, rackup, null);
    }

    /**
     * Creates the handler to bridge the Servlet and Rack worlds.
     * @param runtime
     * @param rackup
     * @param filename
     * @return (Ruby) built Rack Servlet handler
     */
    protected IRubyObject createRackServletWrapper(Ruby runtime, String rackup, String filename) {
        return runtime.executeScript(
            "Rack::Handler::Servlet.new( " +
                "Rack::Builder.new { (" + rackup + "\n) }.to_app " +
            ")", filename
        );
    }
    
    static interface ApplicationObjectFactory {
        IRubyObject create(Ruby runtime) ;
    }

    private RubyInstanceConfig createRuntimeConfig() {
        setupJRubyManagement();
        final RubyInstanceConfig config = new RubyInstanceConfig();
        config.setLoader(Thread.currentThread().getContextClassLoader());
        // Process arguments, namely any that might be in RUBYOPT
        config.processArguments(rackContext.getConfig().getRuntimeArguments());
        
        if (rackContext.getConfig().getCompatVersion() != null) {
            config.setCompatVersion(rackContext.getConfig().getCompatVersion());
        }

        // Don't affect the container and sibling web apps when ENV changes are made inside the Ruby app
        // There are quite a such things made in a typical Bundler based app.
        config.setUpdateNativeENVEnabled(false);

        try { // try to set jruby home to jar file path
            URL resource = RubyInstanceConfig.class.getResource("/META-INF/jruby.home");
            if (resource != null && resource.getProtocol().equals("jar")) {
                String home;
                try { // http://weblogs.java.net/blog/2007/04/25/how-convert-javaneturl-javaiofile
                    home = resource.toURI().getSchemeSpecificPart();
                } catch (URISyntaxException urise) {
                    home = resource.getPath();
                }

                // Trim trailing slash. It confuses OSGi containers...
                if (home.endsWith("/")) {
                    home = home.substring(0, home.length() - 1);
                }
                config.setJRubyHome(home);
            }
        }
        catch (Exception e) { 
            rackContext.log(RackLogger.DEBUG, "won't set-up jruby.home from jar", e);
        }

        return config;
    }
    
    public Ruby newRuntime() throws RaiseException {
        Ruby runtime = Ruby.newInstance(runtimeConfig);
        initializeRuntime(runtime);
        return runtime;
    }
    
    // NOTE: only visible due #jruby/rack/application_spec.rb on JRuby 1.7.x
    void initializeRuntime(Ruby runtime) {
        IRubyObject context = JavaUtil.convertJavaToRuby(runtime, rackContext);
        runtime.getGlobalVariables().set("$servlet_context", context);
        if ( rackContext.getConfig().isIgnoreEnvironment() ) {
            runtime.evalScriptlet("ENV.clear");
            // bundler 1.1.x assumes ENV['PATH'] is a string
            // `ENV['PATH'].split(File::PATH_SEPARATOR)` ...
            runtime.evalScriptlet("ENV['PATH'] = ''");
        }
        runtime.evalScriptlet("require 'rack/handler/servlet'");

        Boolean dechunk = rackContext.getConfig().getBooleanProperty("jruby.rack.response.dechunk");
        if ( dechunk != null ) {
            runtime.evalScriptlet("JRuby::Rack::Response.dechunk = " + dechunk + "");
            // TODO it would be useful by default or when dechunk is on
            // to remove Rack::Chunked from the middleware stack ... ?!
        }
        // NOTE: this is experimental stuff and might change in the future :
        String env = rackContext.getConfig().getProperty("jruby.rack.handler.env");
        // currently supported "env" values are 'default' and 'servlet'
        if ( env != null ) {
            runtime.evalScriptlet("Rack::Handler::Servlet.env = '" + env + "'");
        }
    }

    private RackApplication createApplication(final ApplicationObjectFactory appFactory) {
        return new RackApplicationImpl(appFactory);
    }
    
    /**
     * The application implementation this factory is producing.
     */
    private class RackApplicationImpl extends DefaultRackApplication {
        
        private final Ruby runtime;
        final ApplicationObjectFactory appFactory;
        
        RackApplicationImpl(ApplicationObjectFactory appFactory) {
            this.runtime = newRuntime();
            this.appFactory = appFactory;
        }
        
        @Override
        public void init() {
            try {
                setApplication(appFactory.create(runtime));
            }
            catch (RaiseException e) {
                captureMessage(e);
                throw e;
            }
        }
        
        @Override
        public void destroy() {
            runtime.tearDown(false);
        }
        
    }
    
    private RackApplication createErrorApplication(final ApplicationObjectFactory appFactory) {
        final Ruby runtime = newRuntime();
        return new DefaultErrorApplication() {
            @Override
            public void init() {
                setApplication(appFactory.create(runtime));
            }
            @Override
            public void destroy() {
                runtime.tearDown(false);
            }
        };
    }
    
    private void captureMessage(final RaiseException re) {
        try {
            IRubyObject rubyException = re.getException();
            ThreadContext context = rubyException.getRuntime().getCurrentContext();
            // JRuby-Rack internals (@see jruby/rack/capture.rb) :
            rubyException.callMethod(context, "capture");
            rubyException.callMethod(context, "store");
        }
        catch (Exception e) {
            rackContext.log(RackLogger.INFO, "failed to capture exception message", e);
            // won't be able to capture anything
        }
    }

    private String findConfigRuPathInSubDirectories(final String path, int level) {
        @SuppressWarnings("unchecked")
        final Set<String> entries = rackContext.getResourcePaths(path);
        if (entries != null) {
            String config_ru = path + "config.ru";
            if ( entries.contains(config_ru) ) {
                return config_ru;
            }

            if (level > 0) {
                level--;
                for ( Iterator<String> i = entries.iterator(); i.hasNext(); ) {
                    String subpath = i.next();
                    if (subpath.endsWith("/")) {
                        subpath = findConfigRuPathInSubDirectories(subpath, level);
                        if (subpath != null) {
                            return subpath;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String resolveRackupScript() throws RackInitializationException {
        rackupLocation = "<web.xml>";

        String rackup = rackContext.getConfig().getRackup();
        if (rackup == null) {
            rackup = rackContext.getConfig().getRackupPath();

            if (rackup == null) {
                rackup = findConfigRuPathInSubDirectories("/WEB-INF/", 1);
            }
            if (rackup == null) { // google-appengine gem prefers it at /config.ru
                // appengine misses "/" resources. Search for it directly.
                String rackupPath = rackContext.getRealPath("/config.ru");
                if (rackupPath != null && new File(rackupPath).exists()) {
                    rackup = "/config.ru";
                }
            }

            if (rackup != null) {
                rackupLocation = rackContext.getRealPath(rackup);
                try {
                    rackup = IOHelpers.inputStreamToString(rackContext.getResourceAsStream(rackup));
                }
                catch (IOException e) {
                    rackContext.log(RackLogger.ERROR, "failed to read rackup from '"+ rackup + "' (" + e + ")");
                    throw new RackInitializationException("failed to read rackup input", e);
                }
            }
        }

        return this.rackupScript = rackup;
    }
    
    private void configureDefaults() {
        // configure (default) jruby.rack.request.size.[...] parameters :
        final RackConfig config = rackContext.getConfig();
        Integer iniSize = config.getInitialMemoryBufferSize();
        if (iniSize == null) iniSize = RewindableInputStream.INI_BUFFER_SIZE;
        Integer maxSize = config.getMaximumMemoryBufferSize();
        if (maxSize == null) maxSize = RewindableInputStream.MAX_BUFFER_SIZE;
        if (iniSize.intValue() > maxSize.intValue()) iniSize = maxSize;
        
        RewindableInputStream.setDefaultInitialBufferSize(iniSize);
        RewindableInputStream.setDefaultMaximumBufferSize(maxSize);
    }
    
    private static void setupJRubyManagement() {
        final String jrubyMxEnabled = "jruby.management.enabled";
        if ( ! "false".equalsIgnoreCase( System.getProperty(jrubyMxEnabled) ) ) {
            System.setProperty(jrubyMxEnabled, "true");
        }
    }
    
}