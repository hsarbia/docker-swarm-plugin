package suryagaddipati.jenkinsdockerslaves;

import akka.actor.ActorRef;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import suryagaddipati.jenkinsdockerslaves.docker.DeleteServiceRequest;

import java.util.logging.Level;
import java.util.logging.Logger;

public class DockerSlaveRetentionStrategy extends RetentionStrategy implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(DockerSlaveRetentionStrategy.class.getName());
    private transient boolean terminating;
    private boolean taskCompleted;

    @Override
    public long check(final Computer c) {
        if ( this.taskCompleted  && c.isIdle() && !c.isConnecting()) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(1)) {
                LOGGER.log(Level.FINE, "Disconnecting {0}", c.getName());
                done(c, null);
            }
        }

        // Return one because we want to check every minute if idle.
        return 1;
    }

    @Override
    public void start(final Computer c) {
        c.connect(false);
    }

    @Override
    public boolean isManualLaunchAllowed(final Computer c) {
        return false;
    }

    @Override
    public void taskAccepted(final Executor executor, final Queue.Task task) {

    }

    @Override
    public void taskCompleted(final Executor executor, final Queue.Task task, final long durationMS) {
        this.taskCompleted =true;
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(final Executor executor, final Queue.Task task, final long durationMS, final Throwable problems) {
        this.taskCompleted =true;
        done(executor);
    }

    private void done(final Executor executor) {
        final DockerComputer c = (DockerComputer) executor.getOwner();
        final Queue.Executable exec = executor.getCurrentExecutable();
        LOGGER.log(Level.FINE, "terminating {0} since {1} seems to be finished", new Object[]{c.getName(), exec});

        DockerSwarmPlugin swarmPlugin = Jenkins.getInstance().getPlugin(DockerSwarmPlugin.class);
        ActorRef agentLauncherRef = swarmPlugin.getActorSystem().actorFor("/user/" + c.getName());

        agentLauncherRef.tell(new DeleteServiceRequest(c.getName()),ActorRef.noSender());
        done(c, exec);
    }

    private void done(final Computer c, final Queue.Executable exec) {
        synchronized (this) {
            if (this.terminating) {
                return;
            }
            this.terminating = true;
        }
        Computer.threadPoolForRemoting.submit((Runnable) () -> {
            try {
                final DockerSlave node = (DockerSlave) c.getNode();
                if (node != null) {
                    node.terminate();
                }
            } catch (final Exception e) {
                LOGGER.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                synchronized (DockerSlaveRetentionStrategy.this) {
                    DockerSlaveRetentionStrategy.this.terminating = false;
                }
            }
        });
    }

    public static final class DescriptorImpl extends AbstractDescribableImpl {
        public String getDisplayName() {
            return "Disconnect after 1 min";
        }
    }

}
