/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Frederik Fromm
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.buildblocker;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractProject;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.SubTask;

import java.util.logging.Logger;

/**
 * Queue task dispatcher that evaluates the given blocking jobs in the config of the
 * actual job. If a blocking job is detected, the actual job will stay in the build queue.
 */
@Extension
public class BuildBlockerQueueTaskDispatcher extends QueueTaskDispatcher {

    private static final Logger LOG = Logger.getLogger(BuildBlockerQueueTaskDispatcher.class.getName());

    private MonitorFactory monitorFactory;

    public BuildBlockerQueueTaskDispatcher() {
        monitorFactory = new DefaultMonitorFactory();
    }

    //default scope for testability
    BuildBlockerQueueTaskDispatcher(MonitorFactory monitorFactory) {
        this.monitorFactory = monitorFactory;
    }

    /**
     * Called whenever {@link hudson.model.Queue} is considering if {@link hudson.model.Queue.Item} is ready to execute immediately
     * (which doesn't necessarily mean that it gets executed right away &mdash; it's still subject to
     * executor availability), or if it should be considered blocked.
     * <p/>
     * <p/>
     * Compared to {@link #canTake(hudson.model.Node, hudson.model.Queue.BuildableItem)}, this version tells Jenkins that the task is
     * simply not ready to execute, even if there's available executor. This is more efficient
     * than {@link #canTake(hudson.model.Node, hudson.model.Queue.BuildableItem)}, and it sends the right signal to Jenkins so that
     * it won't use {@link hudson.slaves.Cloud} to try to provision new executors.
     * <p/>
     * <p/>
     * Vetos are additive. When multiple {@link hudson.model.queue.QueueTaskDispatcher}s are in the system,
     * the task is considered blocked if any one of them returns a non-null value.
     * (This relationship is also the same with built-in check logic.)
     * <p/>
     * <p/>
     * If a {@link hudson.model.queue.QueueTaskDispatcher} returns non-null from this method, the task is placed into
     * the 'blocked' state, and generally speaking it stays in this state for a few seconds before
     * its state gets re-evaluated. If a {@link hudson.model.queue.QueueTaskDispatcher} wants the blockage condition
     * to be re-evaluated earlier, call {@link hudson.model.Queue#scheduleMaintenance()} to initiate that process.
     *
     * @return null to indicate that the item is ready to proceed to the buildable state as far as this
     * {@link hudson.model.queue.QueueTaskDispatcher} is concerned. Otherwise return an object that indicates why
     * the build is blocked.
     * @since 1.427
     */
    @Override
    public CauseOfBlockage canRun(Queue.Item item) {
        if (item.task instanceof AbstractProject) {
            BuildBlockerProperty property = getBuildBlockerProperty(item);

            if (property != null && property.isUseBuildBlocker()) {
                CauseOfBlockage subTask = checkForBlock(item, property);
                if (subTask != null) {
                    return subTask;
                }
            }
        }

        return super.canRun(item);
    }

    @Override
    public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
        BuildBlockerProperty property = getBuildBlockerProperty(item);
        if (property != null && property.isUseBuildBlocker()) {
            CauseOfBlockage causeOfBlockage = checkForBlock(node, item, property);
            if (causeOfBlockage != null) {
                return causeOfBlockage;
            }
        }
        return super.canTake(node, item);
    }

    private CauseOfBlockage checkForBlock(Node node, Queue.Item item, BuildBlockerProperty property) {
        if (property.getBlockingJobs() == null) {
            return null;
        }

        SubTask result = checkAccordingToProperties(node, item, property);


        if (result != null) {
            if (result instanceof MatrixConfiguration) {
                result = ((MatrixConfiguration) result).getParent();
            }

            return CauseOfBlockage.fromMessage(Messages._BlockingJobIsRunning(item.getInQueueForString(), result.getDisplayName()));
        }
        return null;
    }

    private SubTask checkAccordingToProperties(Node node, Queue.Item item, BuildBlockerProperty property) {
        BlockingJobsMonitor jobsMonitor = monitorFactory.build(property.getBlockingJobs());

        if (property.isBlockOnGlobalLevel()) {
            SubTask checkAllNodesForRunningBuildsResult = jobsMonitor.checkAllNodesForRunningBuilds();
            if (foundBlocker(checkAllNodesForRunningBuildsResult)) {
                return checkAllNodesForRunningBuildsResult;
            }
            if (property.isScanAllQueueItemStates()) {
                SubTask checkForQueueEntriesResult = jobsMonitor.checkForQueueEntries(item);
                if (foundBlocker(checkForQueueEntriesResult)) {
                    return checkForQueueEntriesResult;
                }
            } else {
                SubTask checkForBuildableQueueEntriesResult = jobsMonitor.checkForBuildableQueueEntries(item);
                if (foundBlocker(checkForBuildableQueueEntriesResult)) {
                    return checkForBuildableQueueEntriesResult;
                }
            }
        }
        if (node != null && property.isBlockOnNodeLevel()) {
            SubTask checkNodeForRunningBuildsResult = jobsMonitor.checkNodeForRunningBuilds(node);
            if (foundBlocker(checkNodeForRunningBuildsResult)) {
                return checkNodeForRunningBuildsResult;
            }
            if (property.isScanAllQueueItemStates()) {
                SubTask checkNodeForQueueEntriesResult = jobsMonitor.checkNodeForQueueEntries(item, node);
                if (foundBlocker(checkNodeForQueueEntriesResult)) {
                    return checkNodeForQueueEntriesResult;
                }
            } else {
                SubTask checkNodeForBuildableQueueEntriesResult = jobsMonitor.checkNodeForBuildableQueueEntries(item, node);
                if (foundBlocker(checkNodeForBuildableQueueEntriesResult)) {
                    return checkNodeForBuildableQueueEntriesResult;
                }
            }
        }
        return null;
    }

    private boolean foundBlocker(SubTask result) {
        return result != null;
    }

    private CauseOfBlockage checkForBlock(Queue.Item item, BuildBlockerProperty blockingJobs) {
        return checkForBlock(null, item, blockingJobs);
    }


    private BuildBlockerProperty getBuildBlockerProperty(Queue.Item item) {
        AbstractProject project = (AbstractProject) item.task;

        return (BuildBlockerProperty) project.getProperty(BuildBlockerProperty.class);
    }
}
