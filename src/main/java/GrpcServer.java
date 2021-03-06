import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.LifeCycle;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class GrpcServer {
    private static Map<String,InetSocketAddress> id2addr = createId2Addr();
    private Server server;
    private static RaftServer raftServer;
    
    public static RaftGroup raftGroup = null;

    public static void main(String[] args) throws InterruptedException, IOException {
        if(args.length == 0){
            args = new String[]{"p1"};
        }
        GrpcServer server = new GrpcServer();
        InetSocketAddress socketAddr = id2addr.get(args[0]);
        if(socketAddr == null){
            System.out.println("Servidor "+args[0]+" não mapeado para o grupo Ratis. Nomes disponíveis: "+SharedInfo.listServerNames());
            return;
        }
        int port = SharedInfo.grpcBasePort + socketAddr.getPort() - SharedInfo.ratisBasePort;
        raftServer = createRaftServer(args[0]);
        raftServer.start();
        server.start(port);

        System.out.println("Server connected to "+port+" port");

        while(raftServer.getLifeCycleState() != LifeCycle.State.CLOSED) {
            TimeUnit.SECONDS.sleep(1);
            //System.out.println("Ratis Cycle State");
        }

        server.blockUntilShutdown();
    }
   
   
    public void start(int port) throws IOException {
        ExecutorService threadPool = Executors.newCachedThreadPool();

        server = ServerBuilder.forPort(port)
                .addService(new GrpcServiceImpl(port))
                .executor(threadPool)
                .build()
                .start();

    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server == null) {
            return;
        }
        server.awaitTermination();
    }

    public static RaftServer createRaftServer(String id) throws IOException{
        RaftGroup raftGroup = getRaftGroup(id);
        RaftPeerId myId = RaftPeerId.valueOf(id);

        RaftProperties properties = new RaftProperties();
        properties.setInt(GrpcConfigKeys.OutputStream.RETRY_TIMES_KEY, Integer.MAX_VALUE);
        GrpcConfigKeys.Server.setPort(properties, id2addr.get(id).getPort());
        RaftServerConfigKeys.setStorageDir(properties, Collections.singletonList(new File(System.getProperty("user.dir")+"/tmp/" + myId)));
        
        return RaftServer.newBuilder()
                .setServerId(myId)
                .setStateMachine(new StateMachineImpl()).setProperties(properties)
                .setGroup(raftGroup)
                .build();
    }

    public static RaftGroup getRaftGroup(String id){
        String raftGroupId = "raft_group____um"; // 16 caracteres.

        //Setup for node all nodes.
        
        List<RaftPeer> addresses = getRaftPeerList(id2addr);

        if(id != null){
            verifyExistance(id, addresses);
        }

        raftGroup = RaftGroup.valueOf(RaftGroupId.valueOf(ByteString.copyFromUtf8(raftGroupId)), addresses);

        return raftGroup;
    }


    public static Map<String,InetSocketAddress> createId2Addr(){
        ServerInfo[] addresses = SharedInfo.listServers;
        Map<String,InetSocketAddress> id2addr = new HashMap<>();
        for(ServerInfo addr : addresses){
            id2addr.put(addr.getName(), new InetSocketAddress(addr.getRatisAddress(), addr.getRatisPort()));
        }
        return id2addr;
    }

    public static List<RaftPeer> getRaftPeerList(Map<String,InetSocketAddress> addr){
        return id2addr.entrySet()
                        .stream()
                        .map(e -> new RaftPeer(RaftPeerId.valueOf(e.getKey()), e.getValue()))
                        .collect(Collectors.toList());
    }

    public static void verifyExistance(String id, List<RaftPeer> addresses){
        RaftPeerId myId = RaftPeerId.valueOf(id);

        if (addresses.stream().noneMatch(p -> p.getId().equals(myId)))
        {
            System.out.println("Identificador " + id + " é inválido.");
        }
    }
}
