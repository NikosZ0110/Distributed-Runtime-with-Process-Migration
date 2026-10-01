package runtime;

import interpreter.CustomException;
import interpreter.ExecutingThread;
import interpreter.Memory;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Semaphore;

public class Runtime {
    static int port = 8080;

    public static class BlockNode {
        Block block;
        InetAddress parentIP;
        int groupID;
        int processID;

        public BlockNode(InetAddress parentIP, int groupID, int processID) {
            this.parentIP = parentIP;
            this.groupID = groupID;
            this.processID = processID;
            block = new Block();
        }
    }
    public static LinkedList<BlockNode> migBlocks = new LinkedList<BlockNode>();
    public static Semaphore migBlocksSem = new Semaphore(1);

    public static class CommunicationInfo {
        public int activeProcesses;
        public ObjectOutputStream oos;
        Socket socket;

        public CommunicationInfo(ObjectOutputStream oos, Socket socket) {
            this.oos = oos;
            this.socket = socket;
            activeProcesses = 0;
        }
    }
    public static Semaphore activeTCPsSem = new Semaphore(1);
    public static HashMap<InetAddress, CommunicationInfo> activeTCPs = new HashMap<>();
    public static boolean checkTCPExistence(InetAddress IP) {
        if (activeTCPs.get(IP) != null) { return true; }
        return false;
    }
    public static void createNewTCPConnection(InetAddress IP) throws IOException {
        Socket socket = new Socket(IP, port);
        OutputStream os = socket.getOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os);
        Receiver receiver = new Receiver(socket, oos);
        Thread receiverThread = new Thread(receiver);
        receiverThread.start();

        activeTCPs.put(IP, new CommunicationInfo(oos, socket));
    }
    public static void removeTCPConnection(InetAddress IP) throws IOException {
        CommunicationInfo toRemove = activeTCPs.get(IP);
        toRemove.socket.close();

        activeTCPs.remove(IP);
    }

    public static class GroupList {
        public LinkedList<ExecutingThread> groupList = new LinkedList<>();
        public Semaphore groupListSem = new Semaphore(1);

        public ObjectOutputStream oos;

        public GroupList(ObjectOutputStream oos) {
            this.oos = oos;
        }
    }
    public static Semaphore groupsSem = new Semaphore(1);
    public static HashMap<InetAddress, GroupList> groups = new HashMap<>();

    public static void addToGroups(InetAddress IP, ExecutingThread groupToAdd) throws InterruptedException {
        groupsSem.acquire();
        for (Map.Entry<InetAddress, GroupList> entry : groups.entrySet()) { //Check if entry exists
            if (!Objects.equals(entry.getKey().getHostAddress(), IP.getHostAddress())) {
                continue;
            }

            //Entry found
            entry.getValue().groupListSem.acquire();
            entry.getValue().groupList.add(groupToAdd); //Add the whole Group with processes made by the local machine to groups HashMap
            entry.getValue().groupListSem.release();
            groupsSem.release();
            return;
        }

        //Entry not found
        GroupList toAdd = new GroupList(null);
        toAdd.groupList.add(groupToAdd); //Add the whole Group with processes made by the local machine to groupsList
        groups.put(IP, toAdd); //Add the entry just made to groups HashMap
        groupsSem.release();
    }
    public static void addToGroups(InetAddress IP, Memory memory, ObjectOutputStream oos) throws InterruptedException, UnknownHostException {
        groupsSem.acquire();
        for (Map.Entry<InetAddress, GroupList> entry : groups.entrySet()) { //Check if entry exists
            if (!Objects.equals(entry.getKey().getHostAddress(), IP.getHostAddress())) {
                continue;
            }

            //Entry found
            entry.getValue().groupListSem.acquire();
            for (ExecutingThread group : entry.getValue().groupList) { //Check if group exists already
                if (group.getThreadId() != memory.getGroupId()) {
                    continue;
                }

                //Group found need expansion
                group.groupSem.acquire();
                group.getGroup().put(memory.getProcessId(), memory);
                group.groupSem.release();
                entry.getValue().groupListSem.release();
                groupsSem.release();
                return;
            }

            //Group not found must be created
            ExecutingThread newThread = new ExecutingThread(memory);
            entry.getValue().groupList.add(newThread);
            entry.getValue().groupListSem.release();
            groupsSem.release();

            Thread thread = new Thread(newThread);
            thread.start();
            return;
        }

        //Entry not found
        ExecutingThread newThread = new ExecutingThread(memory); //Create the group
        GroupList toAdd = new GroupList(oos);
        toAdd.groupList.add(newThread); //Add the group to the groupsList
        groups.put(IP, toAdd); //Add the entry just made to groups HashMap
        groupsSem.release();

        Thread thread = new Thread(newThread);
        thread.start();
    }

    public static void main(String[] args) throws IOException, CustomException, InterruptedException {
        Scanner scanner = new Scanner(System.in);
        String input;
        groups = new HashMap<>();
        int threadId = 0;
        TCPConnectionEstablisher tcpConnectionEstablisher = new TCPConnectionEstablisher();

        while (true) {
            System.out.println(InetAddress.getByName(InetAddress.getLocalHost().getHostAddress())); //InetAddress.getLocalHost()
            input = scanner.nextLine().trim();
            String[] tokens = input.split("\\s+");

            switch (tokens[0]) {
                case "run":
                    ExecutingThread newThread = null;
                    try {
                        newThread = new ExecutingThread(threadId++, tokens);
                        addToGroups(InetAddress.getByName(InetAddress.getLocalHost().getHostAddress()), newThread);
                        Thread thread = new Thread(newThread);
                        thread.start();
                    } catch (CustomException | IOException e) {
                        System.out.println(e.getMessage());
                    }
                    break;
                case "migrate":
                    MigrationThread newMigration = null;
                    try {
                        newMigration = new MigrationThread(tokens);
                    } catch (CustomException e) {
                        System.out.println(e.getMessage());
                    }
                    break;
                case "list":
                    displayThreads();
                    break;
                case "kill":
                    break;
                case "shutdown":
                    shutdown();
                    break;
                default:
                    System.out.println("What the fuck do you mean bro?");
                    break;
            }
        }
    }

    private static void displayThreads() throws UnknownHostException, InterruptedException {
        groupsSem.acquire();
        for (Map.Entry<InetAddress, GroupList> entry: groups.entrySet()) {
            entry.getValue().groupListSem.acquire();
            for (ExecutingThread thread: entry.getValue().groupList) {
                thread.groupSem.acquire();
                for (Map.Entry<Integer, Memory> process: thread.getGroup().entrySet()) {
                    System.out.println("From IP " + entry.getKey() + ": Thread " + process.getValue().groupId + ": " + process.getValue().filename + "(real id = " + process.getKey() + "location = " + process.getValue().id + ").");
                }
                thread.groupSem.release();
            }
            entry.getValue().groupListSem.release();
        }
        groupsSem.release();
    }

    private static void shutdown() throws InterruptedException, UnknownHostException {
        groupsSem.acquire();
        if (groups.isEmpty()) {
            System.exit(69);
        } else {
            System.out.println("There are active processes. Please close them first.");
        }
        groupsSem.release();
        displayThreads();
    }
}