package net.thechunk.playpen.coordinator.local;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.thechunk.playpen.Bootstrap;
import net.thechunk.playpen.Initialization;
import net.thechunk.playpen.coordinator.PlayPen;
import net.thechunk.playpen.networking.TransactionInfo;
import net.thechunk.playpen.networking.TransactionManager;
import net.thechunk.playpen.networking.netty.AuthenticatedMessageInitializer;
import net.thechunk.playpen.p3.P3Package;
import net.thechunk.playpen.p3.PackageManager;
import net.thechunk.playpen.protocol.Commands;
import net.thechunk.playpen.protocol.Coordinator;
import net.thechunk.playpen.protocol.P3;
import net.thechunk.playpen.protocol.Protocol;
import net.thechunk.playpen.utils.AuthUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

@Log4j2
public class Local extends PlayPen {
    public static Local get() {
        if(PlayPen.get() == null) {
            new Local();
        }

        return (Local)PlayPen.get();
    }

    private Map<String, Server> servers = new ConcurrentHashMap<>();

    private PackageManager packageManager = null;

    private ScheduledExecutorService scheduler = null;

    @Getter
    private String coordName;

    @Getter
    private String uuid;

    @Getter
    private String key;

    @Getter
    private Map<String, Integer> resources = new ConcurrentHashMap<>();

    @Getter
    private Set<String> attributes = new ConcurrentSkipListSet<>();

    @Getter
    private boolean enabled = true;

    @Getter
    private Channel channel = null;

    private Local() {
        super();
        packageManager = new PackageManager();
        Initialization.packageManager(packageManager);
    }

    public boolean run() {
        enabled = true;
        resources.clear();
        attributes.clear();

        log.info("Reading local configuration");
        String configStr;
        try {
            configStr = new String(Files.readAllBytes(Paths.get(Bootstrap.getHomeDir().getPath(), "local.json")));
        }
        catch(IOException e) {
            log.fatal("Unable to read configuration file", e);
            return false;
        }

        InetAddress coordIp = null;
        int coordPort = 0;

        try {
            JSONObject config = new JSONObject(configStr);
            coordName = config.getString("name");
            uuid = config.getString("uuid");
            key = config.getString("key");
            coordIp = InetAddress.getByName(config.getString("coord-ip"));
            coordPort = config.getInt("coord-port");

            JSONObject res = config.getJSONObject("resources");
            for(String key : res.keySet()) {
                resources.put(key, res.getInt(key));
            }

            JSONArray attr = config.getJSONArray("attributes");
            for(int i = 0; i < attr.length(); ++i) {
                attributes.add(attr.getString(i));
            }
        }
        catch(Exception e) {
            log.fatal("Unable to read configuration file", e);
            return false;
        }

        if(uuid == null || uuid.isEmpty() || key == null || key.isEmpty()) {
            log.fatal("No UUID or secret key specified in local.json");
            return false;
        }

        if(coordName == null) {
            log.warn("No coordinator name specified in local.json"); // not fatal
        }

        log.info("Starting local coordinator " + uuid);
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            scheduler = Executors.newScheduledThreadPool(4);

            io.netty.bootstrap.Bootstrap b = new io.netty.bootstrap.Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new AuthenticatedMessageInitializer());

            ChannelFuture f = b.connect(coordIp, coordPort).await();

            if(!f.isSuccess()) {
                log.error("Unable to connect to network coordinator at " + coordIp + " port " + coordPort);
                return true; // let's retry!
            }

            channel = f.channel();

            log.info("Connected to network coordinator at " + coordIp + " port " + coordPort);

            sync();
            scheduler.schedule(() -> Local.get().sync(), 5, TimeUnit.MINUTES);

            f.channel().closeFuture().sync();
        }
        catch(InterruptedException e) {
            log.warn("Operation interrupted!", e);
            return false;
        }
        finally {
            scheduler.shutdownNow();
            scheduler = null;

            group.shutdownGracefully();
        }

        return true;
    }

    public Server getServer(String id) {
        return servers.getOrDefault(id, null);
    }

    @Override
    public String getServerId() {
        return coordName;
    }

    @Override
    public PackageManager getPackageManager() {
        return packageManager;
    }

    @Override
    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    @Override
    public boolean send(Protocol.Transaction message, String target) {
        if(channel == null || !channel.isActive()) {
            log.error("Unable to send transaction " + message.getId() + " as the channel is invalid.");
            return false;
        }

        if(!message.isInitialized()) {
            log.error("Transaction is not initialized (protobuf)");
            return false;
        }

        byte[] messageBytes = message.toByteArray();
        Protocol.AuthenticatedMessage auth = Protocol.AuthenticatedMessage.newBuilder()
                .setUuid(uuid)
                .setHash(AuthUtils.createHash(key, messageBytes))
                .setPayload(ByteString.copyFrom(messageBytes))
                .build();

        if(!auth.isInitialized()) {
            log.error("Message is not initialized (protobuf)");
            return false;
        }

        channel.writeAndFlush(auth);
        return true;
    }

    @Override
    public boolean receive(Protocol.AuthenticatedMessage auth, Channel from) {
        if(!auth.getUuid().equals(uuid) || !AuthUtils.validateHash(auth, key)) {
            log.error("Invalid hash on message");
            return false;
        }

        Protocol.Transaction transaction = null;
        try {
            transaction = Protocol.Transaction.parseFrom(auth.getPayload());
        }
        catch(InvalidProtocolBufferException e) {
            log.error("Unable to read transaction from message", e);
            return false;
        }

        TransactionManager.get().receive(transaction, null);
        return true;
    }

    @Override
    public boolean process(Commands.BaseCommand command, TransactionInfo info, String from) {
        throw new NotImplementedException(); // TODO
    }

    public boolean sync() {
        return sendSync();
    }

    public boolean provision(P3Package p3, String uuid, Map<String, String> properties) {
        return provision(p3, uuid, properties, null);
    }

    public boolean provision(P3Package p3, String uuid, Map<String, String> properties, String name) {
        throw new NotImplementedException();
    }

    protected boolean sendSync() {
        Commands.Sync.Builder syncBuilder = Commands.Sync.newBuilder()
                .setEnabled(enabled);

        if(coordName != null)
            syncBuilder.setName(coordName);

        for(Map.Entry<String, Integer> entry : resources.entrySet()) {
            Coordinator.Resource resource = Coordinator.Resource.newBuilder()
                    .setName(entry.getKey())
                    .setValue(entry.getValue())
                    .build();

            syncBuilder.addResources(resource);
        }

        syncBuilder.addAllAttributes(attributes);

        for(Server server : servers.values()) {
            P3.P3Meta meta = P3.P3Meta.newBuilder()
                    .setId(server.getP3().getId())
                    .setVersion(server.getP3().getVersion())
                    .build();

            Coordinator.Server.Builder serverBuilder = Coordinator.Server.newBuilder()
                    .setP3(meta)
                    .setUuid(server.getUuid());

            if(server.getName() != null)
                serverBuilder.setName(server.getName());

            for(Map.Entry<String, String> entry : server.getProperties().entrySet()) {
                Coordinator.Property prop = Coordinator.Property.newBuilder()
                        .setName(entry.getKey())
                        .setValue(entry.getValue())
                        .build();

                serverBuilder.addProperties(prop);
            }

            syncBuilder.addServers(serverBuilder.build());
        }

        Commands.Sync sync = syncBuilder.build();

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.SYNC)
                .setSync(sync)
                .build();

        TransactionInfo info = TransactionManager.get().begin();

        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.SINGLE, command);
        if(message == null) {
            log.error("Unable to build message for sync");
            TransactionManager.get().cancel(info.getId());
            return false;
        }

        log.info("Sending SYNC to network coordinator");
        return TransactionManager.get().send(info.getId(), message, null);
    }

    protected boolean processProvision(Commands.Provision command, TransactionInfo info) {
        Coordinator.Server server = command.getServer();

        if(servers.containsKey(server.getUuid())) {
            log.error("PROVISION contained existing server uuid");
            sendProvisionResponse(info.getId(), false);
            return false;
        }

        final Map<String, String> properties = new HashMap<>();
        for(Coordinator.Property prop : server.getPropertiesList()) {
            properties.put(prop.getName(), prop.getValue());
        }

        final String id = server.getP3().getId();
        final String version = server.getP3().getVersion();
        final String uuid = server.getUuid();
        final String name = server.hasName() ? server.getName() : null;

        P3Package p3 = packageManager.resolve(server.getP3().getId(), server.getP3().getVersion());
        if(p3 == null) {
            log.info("Package " + server.getP3().getId() + " at " + server.getP3().getVersion() + " could not be resolved, requesting from network");
            final String tid = info.getId();

            scheduler.schedule(() -> Local.get().downloadPackageForProvision(tid, id, version, uuid, properties, name), 2, TimeUnit.SECONDS);
            return true;
        }

        if(provision(p3, uuid, properties, name)) {
            return sendProvisionResponse(info.getId(), true);
        }
        else {
            sendProvisionResponse(info.getId(), false);
            return false;
        }
    }

    protected boolean sendProvisionResponse(String tid, boolean ok) {
        TransactionInfo info = TransactionManager.get().getInfo(tid);
        if(info == null) {
            log.error("Unknown transaction " + tid + ", unable to send provision response");
            return false;
        }

        Commands.ProvisionResponse response = Commands.ProvisionResponse.newBuilder()
                .setOk(ok)
                .build();

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.PROVISION_RESPONSE)
                .setProvisionResponse(response)
                .build();

        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.COMPLETE, command);
        if(message == null) {
            log.error("Unable to build message for provision response");
            return false;
        }

        log.info("Sending provision response (" + (ok ? "ok" : "not ok") + ")");

        return TransactionManager.get().send(info.getId(), message, null);
    }

    protected boolean sendPackageRequest(String tid, String id, String version) {
        P3.P3Meta meta = P3.P3Meta.newBuilder()
                .setId(id)
                .setVersion(version)
                .build();

        Commands.PackageRequest request = Commands.PackageRequest.newBuilder()
                .setP3(meta)
                .build();

        Commands.BaseCommand command = Commands.BaseCommand.newBuilder()
                .setType(Commands.BaseCommand.CommandType.PACKAGE_REQUEST)
                .setPackageRequest(request)
                .build();

        TransactionInfo info = TransactionManager.get().begin();

        Protocol.Transaction message = TransactionManager.get()
                .build(info.getId(), Protocol.Transaction.Mode.CREATE, command);
        if(message == null) {
            log.error("Unable to build message for package request");
            TransactionManager.get().cancel(info.getId());
            return false;
        }

        log.info("Sending package request of " + id + " at " + version);
        return TransactionManager.get().send(info.getId(), message, null);
    }

    protected void downloadPackageForProvision(String tid, String id, String version, String uuid, Map<String, String> properties, String name) {
        TransactionInfo info = TransactionManager.get().getInfo(tid);
        if(info == null) {
            log.error("Cannot download package for provision with an invalid transaction id " + tid);
            return;
        }

        TransactionInfo reqInfo = TransactionManager.get().begin();
        if(!sendPackageRequest(reqInfo.getId(), id, version)) {
            log.error("Unable to send package request for " + id + " at " + version + ", cancelling provision");
            sendProvisionResponse(tid, false);
        }

        log.info("Waiting up to 60 seconds for package download");

        // hacky, fix later
        P3Package p3 = null;
        try {
            for(int i = 0; i < 6; ++i) {
                Thread.sleep(1000L * 10L);
                p3 = packageManager.resolve(id, version);
                if(p3 != null)
                    break;

                if(reqInfo.isDone())
                    break;
            }
        }
        catch(InterruptedException e) {
            log.error("Interrupted while waiting for package download");
            return;
        }

        if(p3 == null) {
            log.error("Unable to download package " + id + " at " + version);
            return;
        }

        if(provision(p3, uuid, properties, name)) {
            sendProvisionResponse(tid, true);
        }
        else {
            sendProvisionResponse(tid, false);
        }
    }
}
