package com.anaconda.skein;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class ApplicationMaster implements AMRMClientAsync.CallbackHandler,
       NMClientAsync.CallbackHandler {

  private static final Logger LOG = LogManager.getLogger(ApplicationMaster.class);

  private Configuration conf;

  private Model.Job job;

  private Path appDir;

  private final ConcurrentHashMap<String, String> configuration =
      new ConcurrentHashMap<String, String>();

  private final ConcurrentHashMap<String, List<StreamObserver<Msg.GetKeyResponse>>> alerts =
      new ConcurrentHashMap<String, List<StreamObserver<Msg.GetKeyResponse>>>();

  private final Map<String, ServiceTracker> services =
      new HashMap<String, ServiceTracker>();
  private final List<ServiceTracker> trackers =
      new ArrayList<ServiceTracker>();
  private final Map<ContainerId, TrackerID> containers =
      new HashMap<ContainerId, TrackerID>();

  private Server server;
  private String hostname;
  private int port = -1;

  private int numTotal;
  private int numSucceeded;
  private int numFailed;
  private int numStopped;

  private AMRMClientAsync rmClient;
  private NMClientAsync nmClient;
  private UserGroupInformation ugi;
  private ByteBuffer tokens;

  private void startServer() throws IOException {
    // Setup and start the server
    SslContext sslContext = GrpcSslContexts
        .forServer(new File(".skein.crt"), new File(".skein.pem"))
        .trustManager(new File(".skein.crt"))
        .clientAuth(ClientAuth.REQUIRE)
        .sslProvider(SslProvider.OPENSSL)
        .build();

    server = NettyServerBuilder.forPort(0)
        .sslContext(sslContext)
        .addService(new MasterImpl())
        .executor(Executors.newSingleThreadExecutor())
        .build()
        .start();

    port = server.getPort();

    LOG.info("Server started, listening on " + port);

    Runtime.getRuntime().addShutdownHook(
        new Thread() {
          @Override
          public void run() {
            ApplicationMaster.this.stopServer();
          }
        });
  }

  private void stopServer() {
    if (server != null) {
      LOG.info("Shutting down gRPC server");
      server.shutdown();
      LOG.info("gRPC server shut down");
    }
  }

  private int loadJob() throws Exception {
    try {
      job = MsgUtils.readJob(Msg.Job.parseFrom(new FileInputStream(".skein.proto")));
    } catch (IOException exc) {
      fatal("Issue loading job specification", exc);
    }
    job.validate();
    LOG.info("Job successfully loaded");

    int total = 0;
    for (Model.Service service : job.getServices().values()) {
      total += service.getInstances();
    }
    LOG.info("total instances: " + total);
    return total;
  }

  private synchronized void intializeServices() throws Exception {
    for (Map.Entry<String, Model.Service> entry : job.getServices().entrySet()) {
      String serviceName = entry.getKey();
      Model.Service service = entry.getValue();

      ServiceTracker tracker = new ServiceTracker(serviceName, service);

      services.put(serviceName, tracker);
      trackers.add(tracker);
    }

    // Setup dependents
    for (ServiceTracker tracker : trackers) {
      for (String dep : tracker.service.getDepends()) {
        services.get(dep).dependents.add(tracker);
      }
    }

    // Sort trackers by resources
    Collections.sort(trackers);

    // Startup services
    for (ServiceTracker tracker: trackers) {
      LOG.info("INTIALIZING: " + tracker.name);
      initialize(tracker);
    }
  }

  public void initialize(ServiceTracker tracker) throws IOException {
    Model.Service service = tracker.service;

    // Add appmaster address to environment
    service.getEnv().put("SKEIN_APPMASTER_ADDRESS", hostname + ":" + port);

    tracker.ctx = ContainerLaunchContext.newInstance(
        service.getLocalResources(), service.getEnv(), service.getCommands(),
        null, tokens, null);

    // Request initial containers
    for (int i = 0; i < service.getInstances(); i++) {
      addContainer(tracker);
    }
  }

  @SuppressWarnings("unchecked")
  public String addContainer(ServiceTracker tracker) {
    String id = tracker.nextId();
    if (!tracker.isReady()) {
      LOG.info("WAITING: " + tracker.name + " - " + id);
      tracker.waiting.add(id);
    } else {
      ContainerRequest req =
          new ContainerRequest(tracker.service.getResources(),
                               null, null, Priority.newInstance(0));
      tracker.requested.add(id);
      rmClient.addContainerRequest(req);
      LOG.info("REQUESTED: " + tracker.name + " - " + id);
    }
    return id;
  }

  /* ResourceManager Callbacks */

  @Override
  public synchronized void onContainersCompleted(List<ContainerStatus> containerStatuses) {
    for (ContainerStatus status : containerStatuses) {

      ContainerId cid = status.getContainerId();
      int exitStatus = status.getExitStatus();

      TrackerID pair = containers.get(cid);
      if (pair == null) {
        return;  // release container that was never started
      }
      Container c = pair.tracker.running.remove(pair.id);

      if (exitStatus == ContainerExitStatus.SUCCESS) {
        LOG.info("SUCCEEDED: " + pair.tracker.name + " - " + pair.id);
        pair.tracker.succeeded.put(pair.id, c);
        numSucceeded += 1;
      } else if (exitStatus == ContainerExitStatus.KILLED_BY_APPMASTER) {
        LOG.info("STOPPED: " + pair.tracker.name + " - " + pair.id);
        pair.tracker.stopped.put(pair.id, c);
        numStopped += 1;
      } else {
        LOG.info("FAILED: " + pair.tracker.name + " - " + pair.id);
        pair.tracker.failed.put(pair.id, c);
        numFailed += 1;
      }
    }

    if ((numSucceeded + numStopped) == numTotal || numFailed > 0) {
      shutdown();
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public synchronized void onContainersAllocated(List<Container> newContainers) {
    for (Container c : newContainers) {
      boolean found = false;
      for (ServiceTracker t : trackers) {
        if (t.matches(c.getResource())) {
          found = true;
          String id = Utils.popfirst(t.requested);
          t.running.put(id, c);
          containers.put(c.getId(), new TrackerID(t, id));
          nmClient.startContainerAsync(c, t.ctx);
          LOG.info("RUNNING: " + t.name + " - " + id + " on " + c.getId());

          if (!t.initialRunning && t.requested.size() == 0) {
            t.initialRunning = true;
            for (ServiceTracker dep : t.dependents) {
              // If all dependencies satisfied, launch initial containers
              if (dep.notifyRunning()) {
                for (String id2 : dep.waiting) {
                  ContainerRequest req =
                      new ContainerRequest(dep.service.getResources(), null,
                                           null, Priority.newInstance(0));
                  rmClient.addContainerRequest(req);
                  LOG.info("REQUESTED: " + dep.name + " - " + id2);
                  dep.requested.add(id2);
                }
                dep.waiting.clear();
              }
            }
          }
          break;
        }
      }
      if (!found) {
        // TODO: For some reason YARN allocates extra containers *sometimes*.
        // It's not clear yet if this is a bug in skein or in YARN, for now
        // release extra containers and log the discrepancy.
        LOG.warn("No matching service round for resource: " + c.getResource()
                 + " releasing " + c.getId());
        rmClient.releaseAssignedContainer(c.getId());
      }
    }
  }

  @Override
  public void onShutdownRequest() { shutdown(); }

  @Override
  public void onError(Throwable exc) { shutdown(); }

  @Override
  public void onNodesUpdated(List<NodeReport> nodeReports) {}

  @Override
  public float getProgress() {
    return (float)(numSucceeded + numStopped) / numTotal;
  }

  /* NodeManager Callbacks */

  @Override
  public void onContainerStarted(ContainerId cid, Map<String, ByteBuffer> resp) { }

  @Override
  public void onContainerStatusReceived(ContainerId cid, ContainerStatus status) { }

  @Override
  public void onContainerStopped(ContainerId cid) { }

  @Override
  public synchronized void onStartContainerError(ContainerId containerId, Throwable exc) {
    TrackerID pair = containers.remove(containerId);
    pair.tracker.failed.put(pair.id, pair.tracker.running.remove(pair.id));
    numFailed += 1;
  }

  @Override
  public void onGetContainerStatusError(ContainerId containerId, Throwable exc) {}

  @Override
  public void onStopContainerError(ContainerId containerId, Throwable exc) {
    onStartContainerError(containerId, exc);
  }

  private static void fatal(String msg, Throwable exc) {
    LOG.fatal(msg, exc);
    System.exit(1);
  }

  private static void fatal(String msg) {
    LOG.fatal(msg);
    System.exit(1);
  }

  public void init(String[] args) {
    if (args.length == 0 || args.length > 1) {
      LOG.fatal("Usage: <command> applicationDirectory");
      System.exit(1);
    }
    appDir = new Path(args[0]);
  }

  public void run() throws Exception {
    conf = new YarnConfiguration();

    numTotal = loadJob();

    try {
      hostname = InetAddress.getLocalHost().getHostAddress();
    } catch (UnknownHostException exc) {
      fatal("Couldn't determine hostname for appmaster", exc);
    }

    // Create ugi and add original tokens to it
    String userName = System.getenv(Environment.USER.name());
    LOG.info("user: " + userName);
    ugi = UserGroupInformation.createRemoteUser(userName);

    Credentials credentials = UserGroupInformation.getCurrentUser().getCredentials();
    DataOutputBuffer dob = new DataOutputBuffer();
    credentials.writeTokenStorageToStream(dob);
    // Remove the AM->RM token
    Iterator<Token<?>> iter = credentials.getAllTokens().iterator();
    while (iter.hasNext()) {
      Token<?> token = iter.next();
      if (token.getKind().equals(AMRMTokenIdentifier.KIND_NAME)) {
        iter.remove();
      }
    }
    tokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());

    ugi.addCredentials(credentials);

    rmClient = AMRMClientAsync.createAMRMClientAsync(1000, this);
    rmClient.init(conf);
    rmClient.start();

    nmClient = NMClientAsync.createNMClientAsync(this);
    nmClient.init(conf);
    nmClient.start();

    startServer();

    rmClient.registerApplicationMaster(hostname, port, "");

    intializeServices();

    server.awaitTermination();
  }

  private void shutdown() {
    // wait for completion.
    nmClient.stop();

    // Delete the app directory
    ugi.doAs(
        new PrivilegedAction<Void>() {
          public Void run() {
            try {
              FileSystem fs = FileSystem.get(conf);
              fs.delete(appDir, true);
              LOG.info("Deleted application directory " + appDir);
            } catch (IOException exc) {
              LOG.warn("Failed to delete application directory " + appDir, exc);
            }
            return null;
          }
        });

    FinalApplicationStatus status;
    if (numFailed == 0 && (numSucceeded + numStopped) == numTotal) {
      status = FinalApplicationStatus.SUCCEEDED;
    } else {
      status = FinalApplicationStatus.FAILED;
    }

    String msg = ("Diagnostics."
                  + ", total = " + numTotal
                  + ", succeeded = " + numSucceeded
                  + ", stopped = " + numStopped
                  + ", failed = " + numFailed);

    try {
      rmClient.unregisterApplicationMaster(status, msg, null);
    } catch (Exception ex) {
      LOG.error("Failed to unregister application", ex);
    }

    rmClient.stop();
    System.exit(0);  // Trigger exit hooks
  }

  /** Main entrypoint for the ApplicationMaster. **/
  public static void main(String[] args) {
    ApplicationMaster appMaster = new ApplicationMaster();

    appMaster.init(args);

    try {
      appMaster.run();
    } catch (Throwable exc) {
      fatal("Error running ApplicationMaster", exc);
    }
  }

  private static class ServiceTracker implements Comparable<ServiceTracker> {
    public String name;
    public Model.Service service;
    public ContainerLaunchContext ctx;

    public final Set<String> waiting = new LinkedHashSet<String>();
    public final Set<String> requested = new LinkedHashSet<String>();
    public final Map<String, Container> running = new HashMap<String, Container>();
    public final Map<String, Container> succeeded = new HashMap<String, Container>();
    public final Map<String, Container> failed = new HashMap<String, Container>();
    public final Map<String, Container> stopped = new HashMap<String, Container>();

    public final List<ServiceTracker> dependents = new ArrayList<ServiceTracker>();

    public boolean initialRunning = false;

    private int numWaitingOn;
    private int count = 0;

    public ServiceTracker(String name, Model.Service service) {
      this.name = name;
      this.service = service;
      this.numWaitingOn = service.getDepends().size();
    }

    public boolean isReady() {
      return numWaitingOn == 0;
    }

    public boolean notifyRunning() {
      numWaitingOn -= 1;
      return isReady();
    }

    public String nextId() {
      count += 1;
      return name + "_" + count;
    }

    public boolean matches(Resource r) {
      // requested and requirement <= response
      return requested.size() > 0 && service.getResources().compareTo(r) <= 0;
    }

    public int compareTo(ServiceTracker other) {
      return service.getResources().compareTo(other.service.getResources());
    }
  }

  public static class TrackerID {
    public ServiceTracker tracker;
    public String id;

    public TrackerID(ServiceTracker tracker, String id) {
      this.tracker = tracker;
      this.id = id;
    }
  }

  class MasterImpl extends MasterGrpc.MasterImplBase {
    @Override
    public void getJob(Msg.Empty req, StreamObserver<Msg.Job> resp) {
      resp.onNext(MsgUtils.writeJob(job));
      resp.onCompleted();
    }

    @Override
    public void getService(Msg.ServiceRequest req,
        StreamObserver<Msg.Service> resp) {
      String name = req.getName();
      Model.Service service = job.getServices().get(name);

      if (service == null) {
        resp.onError(Status.INVALID_ARGUMENT
            .withDescription("Unknown Service '" + name + "'")
            .asRuntimeException());
        return;
      }
      resp.onNext(MsgUtils.writeService(service));
      resp.onCompleted();
    }

    @Override
    public void keystoreGet(Msg.GetKeyRequest req,
        StreamObserver<Msg.GetKeyResponse> resp) {
      String key = req.getKey();
      String val = configuration.get(key);
      if (val == null) {
        if (req.getWait()) {
          if (alerts.get(key) == null) {
            alerts.put(key, new ArrayList<StreamObserver<Msg.GetKeyResponse>>());
          }
          alerts.get(key).add(resp);
        } else {
          resp.onError(Status.NOT_FOUND
              .withDescription(key)
              .asRuntimeException());
        }
      } else {
        resp.onNext(Msg.GetKeyResponse.newBuilder().setVal(val).build());
        resp.onCompleted();
      }
    }

    @Override
    public void keystoreSet(Msg.SetKeyRequest req,
        StreamObserver<Msg.Empty> resp) {
      String key = req.getKey();
      String val = req.getVal();

      configuration.put(key, val);

      resp.onNext(MsgUtils.EMPTY);
      resp.onCompleted();

      // Handle alerts
      List<StreamObserver<Msg.GetKeyResponse>> callbacks = alerts.get(key);
      if (callbacks != null) {
        Msg.GetKeyResponse cbResp =
            Msg.GetKeyResponse.newBuilder().setVal(val).build();
        for (StreamObserver<Msg.GetKeyResponse> cb : callbacks) {
          try {
            cb.onNext(cbResp);
            cb.onCompleted();
          } catch (StatusRuntimeException exc) {
            if (exc.getStatus().getCode() != Status.Code.CANCELLED) {
              LOG.info("Callback failed on key: " + key
                       + ", status: " + exc.getStatus());
            }
          }
        }
        alerts.remove(key);
      }
    }

    @Override
    public void keystoreDel(Msg.DelKeyRequest req, StreamObserver<Msg.Empty> resp) {
      String key = req.getKey();
      if (configuration.remove(key) == null) {
        resp.onError(Status.NOT_FOUND.withDescription(key).asRuntimeException());
      } else {
        resp.onNext(MsgUtils.EMPTY);
        resp.onCompleted();
      }
    }

    @Override
    public void keystore(Msg.Empty req,
        StreamObserver<Msg.KeystoreResponse> resp) {
      resp.onNext(Msg.KeystoreResponse
          .newBuilder()
          .putAllItems(configuration)
          .build());
      resp.onCompleted();
    }
  }
}