package org.domeos.framework.engine.k8s.updater;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerList;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.domeos.exception.DeploymentEventException;
import org.domeos.exception.K8sDriverException;
import org.domeos.exception.TimeoutException;
import org.domeos.framework.api.consolemodel.deployment.EnvDraft;
import org.domeos.framework.api.model.LoadBalancer.LoadBalancer;
import org.domeos.framework.api.model.deployment.Deployment;
import org.domeos.framework.api.model.deployment.Policy;
import org.domeos.framework.api.model.deployment.Version;
import org.domeos.framework.engine.k8s.DomeOSSecretBuilder;
import org.domeos.framework.engine.k8s.RcBuilder;
import org.domeos.framework.engine.k8s.model.DeploymentUpdatePhase;
import org.domeos.framework.engine.k8s.model.DeploymentUpdateStatus;
import org.domeos.framework.engine.k8s.model.UpdatePhase;
import org.domeos.framework.engine.k8s.util.KubeUtils;
import org.domeos.framework.engine.k8s.util.PodUtils;
import org.domeos.framework.engine.k8s.util.RCUtils;
import org.domeos.framework.engine.k8s.util.SecretUtils;
import org.domeos.global.GlobalConstant;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by anningluo on 2015/12/16.
 */
public class  DeploymentUpdater {
    private KubeUtils client;
    private Deployment deployment;
    private Version dstVersion;
    // lock sequence rcUpdaterLock -> status -> rcUpdater.status
    final private DeploymentUpdateStatus status = new DeploymentUpdateStatus();
    private ReplicationControllerUpdater rcUpdater = ReplicationControllerUpdater.EmptyUpdater();
    private Lock rcUpdaterLock = new ReentrantLock();
    private Map<String, String> rcSelector = null;
    private Future future;
    private boolean keepRcQuantity = true; // if replicas not set, keep rc
                                           // replicas the same as old version
    private int replicas;
    private ReplicationController targetRC = null;
    private static ExecutorService executors = Executors.newCachedThreadPool();
    private static Logger logger = LoggerFactory.getLogger(DeploymentUpdater.class);
    private Policy policy;

    public DeploymentUpdater(KubeUtils client, Deployment deployment, Version version, List<EnvDraft> extraEnvs) {
        this.client = client;
        this.deployment = deployment;
        this.dstVersion = version;
        this.keepRcQuantity = true;
        this.targetRC = new RcBuilder(deployment, null, version, extraEnvs, replicas).build();
    }

    public DeploymentUpdater(KubeUtils client, Deployment deployment, Version version, List<EnvDraft> extraEnvs,
            Policy policy, List<LoadBalancer> lbs) {
        this.client = client;
        this.deployment = deployment;
        this.dstVersion = version;
        this.keepRcQuantity = true;
        this.targetRC = new RcBuilder(deployment, lbs, version, extraEnvs, replicas).build();
        this.policy = policy;
    }

    public DeploymentUpdater(KubeUtils client, Deployment deployment, Version version, int replicas,
            List<EnvDraft> extraEnvs) {
        this.client = client;
        this.deployment = deployment;
        this.dstVersion = version;
        this.keepRcQuantity = false;
        this.replicas = replicas;
        this.targetRC = new RcBuilder(deployment, null, version, extraEnvs, replicas).build();
    }

    public DeploymentUpdater(KubeUtils client, Deployment deployment, Version version, int replicas,
            List<EnvDraft> extraEnvs, Policy policy, List<LoadBalancer> lbs) {
        this.client = client;
        this.deployment = deployment;
        this.dstVersion = version;
        this.keepRcQuantity = false;
        this.replicas = replicas;
        this.targetRC = new RcBuilder(deployment, lbs, version, extraEnvs, replicas).build();
        this.policy = policy;
    }

    public void start() {
        if (client == null || deployment == null || dstVersion == null) {
            return;
        }
        synchronized (status) {
            if (status.getPhase() != DeploymentUpdatePhase.Unknown) {
                String message = "try start one updater which has been start, deployId=" + deployment.getId()
                        + ", dstVersionId=" + dstVersion.getVersion();
                status.failed(message);
                logger.error(message);
                return;
            }
            status.start();
        }
        rcSelector = new HashMap<>();
        rcSelector.put(GlobalConstant.DEPLOY_ID_STR, String.valueOf(deployment.getId()));
        future = executors.submit(new UpdateDeployment());
    }

    private ReplicationController selectMaxVersionRC(Map<String, String> rcSelector)
            throws IOException, K8sDriverException {
        ReplicationControllerList rcList = client.listReplicationController(rcSelector);
        if (rcList == null || rcList.getItems().size() == 0) {
            // failedPhase("no previous replication controller found");
            return null;
        }
        ReplicationController maxVersionRC = null;
        int maxVersionId = -1;
        int dstVersionId = dstVersion.getVersion();
        for (ReplicationController rc : rcList.getItems()) {
            // ** ignore 0 replicas ?? is it right, this will ignore rc whose
            // replicas is zero
            if (rc.getSpec().getReplicas() == 0) {
                continue;
            }
            // ** select max version replication controller
            int currentVersionId = Integer.parseInt(rc.getMetadata().getLabels().get(GlobalConstant.VERSION_STR));
            if (currentVersionId > maxVersionId && currentVersionId != dstVersionId) {
                maxVersionRC = rc;
                maxVersionId = currentVersionId;
            }
        }
        return maxVersionRC;
    }

    private void startOneStepRCUpdate(ReplicationController srcRC)
            throws DeploymentEventException {
        ReplicationControllerUpdater oneStepUpdater = ReplicationControllerUpdater.RollingUpdater(client, srcRC,
                targetRC, policy);
        freshUpdater(oneStepUpdater);
        oneStepUpdater.update();
    }

    private void deleteOtherRC()
            throws IOException, K8sDriverException, DeploymentEventException {
        ReplicationControllerList rcList = client.listReplicationController(rcSelector);
        if (rcList == null || rcList.getItems() == null || rcList.getItems().get(0) == null) {
            return;
        }
        for (ReplicationController rc : rcList.getItems()) {
            if (Integer.parseInt(rc.getMetadata().getLabels().get(GlobalConstant.VERSION_STR)) == dstVersion
                    .getVersion()) {
                continue;
            }
            PodList podList = client.listPod(RCUtils.getSelector(rc));
            client.deleteReplicationController(RCUtils.getName(rc));
            if (podList != null && podList.getItems() != null) {
                for (Pod pod : podList.getItems()) {
                    client.deletePod(PodUtils.getName(pod));
                }
            }
        }
    }

    public void stop() {
        rcUpdaterLock.lock();
        try {
            if (rcUpdater != null) {
                rcUpdater.close();
                rcUpdater = null;
            }
        } finally {
            rcUpdaterLock.unlock();
        }
        if (!(future == null || future.isCancelled())) {
            future.cancel(true);
        }
    }

    public void close() {
        rcUpdaterLock.lock();
        try {
            if (rcUpdater != null) {
                rcUpdater.close();
            }
        } finally {
            rcUpdaterLock.unlock();
        }
    }

    public DeploymentUpdateStatus getStatus() {
        synchronized (status) {
            if (future != null && future.isDone() && status.getPhase() != DeploymentUpdatePhase.Failed
                    && status.getPhase() != DeploymentUpdatePhase.Succeed) {
                String message = "executor thread is terminated, but status is not, some unknown exception may happen in update deployment";
                status.failed(message);
                logger.error(message);
            }
            return new DeploymentUpdateStatus(status);
        }
    }

    private void failedPhase(String reason) {
        synchronized (status) {
            status.failed(reason);
        }
        logger.error(reason);
    }

    private void succeedPhase() {
        synchronized (status) {
            status.succeed();
        }
    }

    private void startPhase() {
        synchronized (status) {
            status.start();
        }
    }

    private void stopPhase() {
        synchronized (status) {
            status.stop();
        }
    }

    private void runPhase() {
        synchronized (status) {
            status.run();
        }
    }

    /*
     * private UpdateStatus getRCUpdateStatus() { synchronized (rcUpdater) {
     * return rcUpdater.getStatus(); } }
     */

    private void freshUpdater(ReplicationControllerUpdater updater) {
        rcUpdaterLock.lock();
        try {
            rcUpdater = updater;
        } finally {
            rcUpdaterLock.unlock();
        }
    }

    private void waitRCSuccess(String rcName, long interBreak, long timeout)
            throws IOException, K8sDriverException, TimeoutException, DeploymentEventException {
        long startTimePoint = System.currentTimeMillis();
        ReplicationController rc = client.replicationControllerInfo(rcName);
        if (rc == null || rc.getSpec() == null || rc.getSpec().getSelector() == null) {
            logger.error("get target rc error, no such rc!");
            throw new DeploymentEventException("get target rc=" + rcName + " is null");
        }
        Map<String, String> podSelector = rc.getSpec().getSelector();
        PodList podList = client.listPod(podSelector);
        if (podList == null || podList.getItems() == null) {
            logger.error("no pod info for rc(name=" + rcName + ")");
            throw new DeploymentEventException("get podList with selector=" + podSelector + ", but return null");
        }
        while (PodUtils.getPodReadyNumber(podList.getItems()) != replicas || podList.getItems().size() != replicas) {
            if (System.currentTimeMillis() - startTimePoint > timeout) {
                throw new TimeoutException("TIMEOUT: wait rc=" + rcName + " for " + timeout + "millisecond.");
            }
            try {
                Thread.sleep(interBreak);
            } catch (InterruptedException e) {
                // ignore and continue;
            }
            podList = client.listPod(podSelector);
            if (podList == null || podList.getItems() == null) {
                logger.error("no pod info for rc(name=" + rcName + ")");
                throw new DeploymentEventException("get podList with selector=" + podSelector + ", but return null");
            }
        }
    }

    public boolean checkStatus() {
        if (targetRC.getSpec().getReplicas() > 0) {
            try {
                waitRCSuccess(RCUtils.getName(targetRC), 1000, 5 * 60 * 1000);
                succeedPhase();
                return true;
            } catch (IOException | K8sDriverException | TimeoutException | DeploymentEventException e) {
                logger.warn("catch exception wait rc success, message is " + e.getMessage());
            }
        }
        return false;
    }

    private class UpdateDeployment implements Runnable {
        @Override
        public void run() {
            try {
                ReplicationController currentTargetRC;
                int currentTargetReplicas;

                // ** check whether target RC exist, create it if not
                ReplicationControllerList targetRCList = client.listReplicationController(targetRC.getMetadata()
                        .getLabels());
                if (targetRCList == null || targetRCList.getItems() == null || targetRCList.getItems().size() == 0) {
                    // ** ** no target rc exist, create new
                    // create secret before the create of rc
                    // judge the registry is belong to domeos or not
                    if (SecretUtils.haveDomeOSRegistry(dstVersion.getContainerDrafts())) {// domeos
                                                                                          // registry
                        try {
                            if (client.secretInfo(GlobalConstant.SECRET_NAME_PREFIX + deployment.getNamespace()) == null) {
                                client.createSecret(new DomeOSSecretBuilder(GlobalConstant.SECRET_NAME_PREFIX
                                        + deployment.getNamespace(), SecretUtils.getDomeOSImageSecretData()).build());
                            }
                        } catch (K8sDriverException | JSONException e) {
                            throw new DeploymentEventException("kubernetes exception with message=" + e.getMessage());
                        }
                    }
                    targetRC.getSpec().setReplicas(0);
                    client.createReplicationController(targetRC);
                } else if (targetRCList.getItems().size() != 1) {
                    // ** ** make sure only one rc for one version in kubernetes
                    failedPhase("update deployment(id=" + deployment.getId() + ") to version="
                            + dstVersion.getVersion() + ", but more than one rc exist for that version");
                    return;
                } else {
                    // ** ** attach to exist rc of target version
                    targetRC = targetRCList.getItems().get(0);
                    targetRC.getSpec().setReplicas(0);
                }

                // ** find first rc to update
                ReplicationController rc = selectMaxVersionRC(rcSelector);
                // ** start update
                while (rc != null) {
                    // ** get current target rc number
                    currentTargetRC = client.replicationControllerInfo(RCUtils.getName(targetRC));
                    currentTargetReplicas = currentTargetRC.getSpec().getReplicas();
                    if (keepRcQuantity) {
                        // ** ** in this case, the number pod of dst version
                        // will be identified with old version
                        targetRC.getSpec().setReplicas(currentTargetReplicas + rc.getSpec().getReplicas());
                    } else if (currentTargetReplicas >= replicas) {
                        // ** ** in this case, just delete old rc and return
                        deleteOtherRC();
                        // todo : check rc is really deleted
                        succeedPhase();
                        return;
                    } else {
                        // ** ** ensure not more than $replicas pod will be
                        // created
                        targetRC.getSpec().setReplicas(
                                Math.min(replicas, currentTargetReplicas + rc.getSpec().getReplicas()));
                    }
                    // ** start rc updater
                    startOneStepRCUpdate(rc);
                    // ** check update success
                    rcUpdaterLock.lock();
                    try {
                        if (rcUpdater.getStatus().getPhase() == UpdatePhase.Failed) {
                            // in this case, status could not be modified, get
                            // status
                            // will moidfy it later.
                            failedPhase(rcUpdater.getStatus().getReason());
                            return;
                        }
                    } finally {
                        rcUpdaterLock.unlock();
                    }
                    // ** find next rc to update
                    rc = selectMaxVersionRC(rcSelector);
                }
                // ** check whether more pod is needed for target rc
                currentTargetRC = client.replicationControllerInfo(RCUtils.getName(targetRC));
                currentTargetReplicas = currentTargetRC.getSpec().getReplicas();
                if (!keepRcQuantity && currentTargetReplicas < replicas) {
                    client.scaleReplicationController(RCUtils.getName(currentTargetRC), replicas);
                }
                long timeout = (currentTargetReplicas > 0) ? currentTargetReplicas : 1L;
                waitRCSuccess(RCUtils.getName(targetRC), 1000, timeout * 5 * 60 * 1000);
                succeedPhase();
            } catch (IOException | K8sDriverException e) {
                failedPhase("kubernetes failed with message=" + e.getMessage());
            } catch (Exception e) {
                failedPhase("update deployment(id=" + deployment.getId() + ") failed, exception=" + e);
            }
        }
    }
}
