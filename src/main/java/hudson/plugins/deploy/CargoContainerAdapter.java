package hudson.plugins.deploy;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.util.VariableResolver;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.codehaus.cargo.container.Container;
import org.codehaus.cargo.container.ContainerException;
import org.codehaus.cargo.container.ContainerType;
import org.codehaus.cargo.container.configuration.Configuration;
import org.codehaus.cargo.container.configuration.ConfigurationType;
import org.codehaus.cargo.container.deployable.Deployable;
import org.codehaus.cargo.container.deployable.EAR;
import org.codehaus.cargo.container.deployable.WAR;
import org.codehaus.cargo.container.deployer.Deployer;
import org.codehaus.cargo.generic.ContainerFactory;
import org.codehaus.cargo.generic.DefaultContainerFactory;
import org.codehaus.cargo.generic.configuration.ConfigurationFactory;
import org.codehaus.cargo.generic.configuration.DefaultConfigurationFactory;
import org.codehaus.cargo.generic.deployer.DefaultDeployerFactory;
import org.codehaus.cargo.generic.deployer.DeployerFactory;

/**
 * Provides container-specific glue code.
 *
 * <p>
 * To support remote operations as an inner class, marking the class as
 * serializable.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class CargoContainerAdapter extends ContainerAdapter implements Serializable {
    private static final long serialVersionUID = 5369849174353254743L;

    /**
     * Returns the container ID used by Cargo.
     *
     * @return the id of the container
     */
    protected abstract String getContainerId();

    /**
     * Fills in the {@link Configuration} object.
     *
     * @param config   the configuration of the adapter
     * @param envVars  the environmental variables of the build
     * @param resolver the variable resolver
     */
    protected abstract void configure(Configuration config, EnvVars envVars, VariableResolver<String> resolver);

    protected Container getContainer(ConfigurationFactory configFactory, ContainerFactory containerFactory, String id, EnvVars envVars, VariableResolver<String> resolver) {
        Configuration config = configFactory.createConfiguration(id, ContainerType.REMOTE, ConfigurationType.RUNTIME);
        configure(config, envVars, resolver);
        return containerFactory.createContainer(id, ContainerType.REMOTE, config);
    }

    protected void deploy(DeployerFactory deployerFactory, final TaskListener listener, Container container, File f, String contextPath, int attempts) {
        Deployer deployer = deployerFactory.createDeployer(container);

        listener.getLogger().println("[DeployPublisher][INFO] Deploying " + f + " to container " + container.getName() + " with context " + contextPath + " and try " + attempts +"/4");
        deployer.setLogger(new LoggerImpl(listener.getLogger()));


        String extension = FilenameUtils.getExtension(f.getAbsolutePath());
        if ("WAR".equalsIgnoreCase(extension)) {
            WAR war = createWAR(f);
            if (!StringUtils.isEmpty(contextPath)) {
                war.setContext(contextPath);
            }
            deployWithRetries(deployer, listener, war, attempts);
        } else if ("EAR".equalsIgnoreCase(extension)) {
            EAR ear = createEAR(f);
            deployWithRetries(deployer, listener, ear, attempts);
        } else {
            throw new RuntimeException("Extension File Error. Unsupported: .\"" + extension + "\"");
        }
    }

    protected void deployWithRetries(Deployer deployer, TaskListener listener, Deployable war, int attempts) {
        for (int tries = 1; tries <= attempts; tries++) {
            try {
                deployer.redeploy(war);
                return;
            } catch (ContainerException ce) {
                listener.getLogger().println("Deploy Problem [attempt " + tries + " of " + attempts + "] - " + ce.getMessage());
                if (tries >= attempts) {
                    throw ce;
                } else {
                    try {
                        // Wait a little bit, the tomcat could be restarting, then try it again.
                        Thread.sleep(15 * 1000);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
        }
    }


    /**
     * Creates a Deployable object WAR from the given file object.
     *
     * @param deployableFile The deployable file to create the Deployable from.
     * @return A Deployable object.
     */
    protected WAR createWAR(File deployableFile) {
        return new WAR(deployableFile.getAbsolutePath());
    }

    /**
     * Expands an encoded environment variable. Ex. if HOME=/user/alex, expands '${HOME}' to '/user/alex'
     *
     * @param envVars  the environment variables of the build
     * @param resolver unused
     * @param variable the variable to expand
     * @return the value of the expanded variable
     */
    protected String expandVariable(EnvVars envVars, VariableResolver<String> resolver, String variable) {
        return Util.replaceMacro(envVars.expand(variable), resolver);
    }


    /**
     * Creates a Deployable object EAR from the given file object.
     *
     * @param deployableFile The deployable file to create the Deployable from.
     * @return A deployable object.
     */
    protected EAR createEAR(File deployableFile) {
        return new EAR(deployableFile.getAbsolutePath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void redeployFile(FilePath war, final String contextPath, final int attempts, final Run<?, ?> run, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        EnvVars envVars = new EnvVars();
        if (run instanceof AbstractBuild) {
            AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) run;
            envVars = build.getEnvironment(listener);
        }
        war.act(new DeployCallable(this, getContainerId(), envVars, listener, contextPath, attempts));
    }

    public static class DeployCallable extends MasterToSlaveFileCallable<Boolean> {
        private static final long serialVersionUID = 2695944029141469818L;

        private CargoContainerAdapter adapter;
        private String containerId;
        private TaskListener listener;
        private String contextPath;
        private EnvVars envVars;
        private int attempts;

        public DeployCallable (CargoContainerAdapter adapter, String containerId, EnvVars envVars,
                              TaskListener listener, String contextPath, int attempts) {
            this.adapter = adapter;
            this.containerId = containerId;
            this.envVars = envVars;
            this.listener = listener;
            this.contextPath = contextPath;
            this.attempts = attempts;
        }

        public Boolean invoke(File f, VirtualChannel channel) throws IOException {
            if (!f.exists()) {
                listener.error(Messages.DeployPublisher_NoSuchFile(f));
                return true;
            }

            ClassLoader cl = getClass().getClassLoader();
            DeployerFactory deployerFactory = new DefaultDeployerFactory(cl);
            ConfigurationFactory configFactory = new DefaultConfigurationFactory(cl);
            ContainerFactory containerFactory = new DefaultContainerFactory(cl);

            VariableResolver<String> resolver = new VariableResolver.ByMap<>(envVars);
            Container container = adapter.getContainer(configFactory, containerFactory, containerId, envVars, resolver);
            adapter.deploy(deployerFactory, listener, container, f, Util.replaceMacro(envVars.expand(contextPath), resolver), attempts);

            return true;
        }
    }
}
