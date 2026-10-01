package interpreter;

import runtime.*;
import runtime.Runtime;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Semaphore;

import static runtime.Runtime.*;

public class ExecutingThread implements Runnable {
    InetAddress machine;
    private final HashMap<Integer, Memory> group; // Key = id, Value = Memory
    public final int threadId;
    public final SNDRCVBlock sndrcvBlock;
    public Semaphore groupSem = new Semaphore(1);
    public Semaphore allProcessesAreImmigrantsSem = new Semaphore(0);
    public boolean allProcessesAreImmigrantsFlag = false;

    public ExecutingThread(int threadId, String[] tokens) throws CustomException, IOException {
        if (tokens.length == 1) {
            throw new CustomException("run: missing operand");
        }
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);
        int processId = 0;
        this.threadId = threadId;
        group = new HashMap<>();
        machine = InetAddress.getByName(InetAddress.getLocalHost().getHostAddress());

        File file;
        Scanner scanner;
        List<String> tmpArgs = new ArrayList<>();

        sndrcvBlock = new SNDRCVBlock();
        try {
            for (String arg: args) {
                if (!arg.equals("||")) {
                    tmpArgs.add(arg);
                } else {
                    file = new File(tmpArgs.getFirst());
                    scanner = new Scanner(file);
                    scanner.close();
                    String[] finalArgs = tmpArgs.toArray(new String[0]);
                    group.put(processId, new Memory(threadId, processId, finalArgs));
                    processId++;
                    tmpArgs = new ArrayList<>();
                }
            }

            // Repeat the file opening process for the last arg
            file = new File(tmpArgs.getFirst());
            scanner = new Scanner(file);
            scanner.close();
            String[] finalArgs = tmpArgs.toArray(new String[0]);
            group.put(processId, new Memory(threadId, processId, finalArgs));

        } catch (FileNotFoundException e) {
            System.out.println("Thread " + threadId + ": " + tmpArgs.getFirst() + ": not found");
            System.out.println("Thread " + threadId + " finished.");
            return;
        } catch (UnknownHostException e) {
            System.out.println("Thread " + threadId + ": " + e.getMessage());
            System.out.println("Thread " + threadId + " finished.");
            return;
        }

        for (Map.Entry<Integer, Memory> entry: group.entrySet()) {
            entry.getValue().parser = new Parser(entry.getValue());
        }
    }

    public ExecutingThread(Memory memory) throws UnknownHostException {
        threadId = memory.groupId;
        group = new HashMap<>();
        sndrcvBlock = new SNDRCVBlock();
        machine = memory.parentIP;

        group.put(memory.id, memory);
    }

    public HashMap<Integer, Memory> getGroup() { return group; }
    public int getThreadId() { return threadId; }
    public synchronized void removeFromGroup(int id) { group.remove(id); }

    @Override
    public void run() {
        try {
            try {
                roundRobin();
            } catch (IOException | InterruptedException | CustomException e) {
                System.out.println("Thread " + threadId + ": " + e.getMessage());
                System.out.println("Thread " + threadId + " finished.");
                groupsSem.acquire();
                Runtime.GroupList toRemove = groups.get(machine);
                toRemove.groupListSem.acquire();
                toRemove.groupList.remove(this);
                if (toRemove.groupList.isEmpty()) {
                    groups.remove(machine);
                }
                toRemove.groupListSem.release();
                groupsSem.release();
                return;
            }

            System.out.println("Thread " + threadId + " finished.");
            groupsSem.acquire();
            Runtime.GroupList toRemove = groups.get(machine);
            toRemove.groupListSem.acquire();
            toRemove.groupList.remove(this);
            if (toRemove.groupList.isEmpty()) {
                groups.remove(machine);
            }
            toRemove.groupListSem.release();
            groupsSem.release();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private void roundRobin() throws CustomException, IOException, InterruptedException {

        while (true) {
            groupSem.acquire();
            if (group.isEmpty()) {
                groupSem.release();
                return;
            }

            int processCnt = 0;
            for (Map.Entry<Integer, Memory> process: group.entrySet()) {
                if (process.getValue().id != -1) { break; } //id == -1 means that process is immigrant
                processCnt++;
            }

            if (processCnt == group.size()) {
                //There still are processes but are all immigrants
                //So I will block
                //I will unblock in any received message to check if I have to remove a process
                allProcessesAreImmigrantsFlag = true;
                groupSem.release();
                allProcessesAreImmigrantsSem.acquire();
                continue;
            }

            for (int i = 0; i < group.size(); i++) {
                List<Map.Entry<Integer, Memory>> entryList = new ArrayList<>(group.entrySet());
                Map.Entry<Integer, Memory> entry = entryList.get(i);
                Integer key = entry.getKey();
                Memory mem = entry.getValue();

                if (mem.id == -1) { continue; }
                if (entry.getValue().sleeping) { continue; }

                Instruction instruction = mem.parser.parse(mem);
                if (instruction == null) {
                    throw new CustomException("Error in program " + entry.getValue().filename + ". No RET found.\n");
                } else {
                    executeInstruction(instruction, mem);
                }
            }

            groupSem.release();
        }
    }

    private void executeInstruction(Instruction instruction, Memory memory) throws CustomException, IOException, InterruptedException {
        Variables tmpVarVal1;
        Variables tmpVarVal2;
        Variables tmpVar;
        String[] tmpTokens;
        boolean found = false;
        boolean foundButImmigrant = false;

        boolean dataSent = false;
        int i;

        switch (instruction.operation) {
            case "SET":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    }
                } else if (instruction.varVal1.charAt(0) == '"') {
                    tmpVarVal1 = new Variables(Variables.VariableType.STRING, "tmp", instruction.varVal1.substring(1, instruction.varVal1.length() - 1));
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                tmpVar = memory.getVariableByName(instruction.varName);
                if (tmpVar != null) { // varName already exist in memory
                    tmpVar.type = tmpVarVal1.type;
                    tmpVar.value = tmpVarVal1.value; // Replace its value
                } else {
                    memory.variables.add(new Variables(tmpVarVal1.type, instruction.varName, tmpVarVal1.value));
                }

                break;
            case "ADD":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                tmpVar = memory.getVariableByName(instruction.varName);
                if (tmpVar != null) { // varName already exist in memory
                    tmpVar.value = (Integer) tmpVarVal1.value + (Integer) tmpVarVal2.value; // Replace its value
                    tmpVar.type = tmpVarVal1.type;
                } else {
                    memory.variables.add(new Variables(tmpVarVal1.type, instruction.varName, (Integer) tmpVarVal1.value + (Integer) tmpVarVal2.value));
                }

                break;
            case "SUB":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                tmpVar = memory.getVariableByName(instruction.varName);
                if (tmpVar != null) { // varName already exist in memory
                    tmpVar.value = (Integer) tmpVarVal1.value - (Integer) tmpVarVal2.value; // Replace its value
                    tmpVar.type = tmpVarVal1.type;
                } else {
                    memory.variables.add(new Variables(tmpVarVal1.type, instruction.varName, (Integer) tmpVarVal1.value - (Integer) tmpVarVal2.value));
                }

                break;
            case "MUL":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                tmpVar = memory.getVariableByName(instruction.varName);
                if (tmpVar != null) { // varName already exist in memory
                    tmpVar.value = (Integer) tmpVarVal1.value * (Integer) tmpVarVal2.value; // Replace its value
                    tmpVar.type = tmpVarVal1.type;
                } else {
                    memory.variables.add(new Variables(tmpVarVal1.type, instruction.varName, (Integer) tmpVarVal1.value * (Integer) tmpVarVal2.value));
                }

                break;
            case "DIV":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                tmpVar = memory.getVariableByName(instruction.varName);
                if (tmpVar != null) { // varName already exist in memory
                    tmpVar.value = (Integer) tmpVarVal1.value / (Integer) tmpVarVal2.value; // Replace its value
                    tmpVar.type = tmpVarVal1.type;
                } else {
                    memory.variables.add(new Variables(tmpVarVal1.type, instruction.varName, (Integer) tmpVarVal1.value / (Integer) tmpVarVal2.value));
                }

                break;
            case "MOD":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                tmpVar = memory.getVariableByName(instruction.varName);
                if (tmpVar != null) { // varName already exist in memory
                    tmpVar.value = (Integer) tmpVarVal1.value % (Integer) tmpVarVal2.value; // Replace its value
                    tmpVar.type = tmpVarVal1.type;
                } else {
                    memory.variables.add(new Variables(tmpVarVal1.type, instruction.varName, (Integer) tmpVarVal1.value % (Integer) tmpVarVal2.value));
                }

                break;
            case "BGT":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                if ((Integer)tmpVarVal1.value > (Integer)tmpVarVal2.value) {
                    boolean res = continueFromLabel(memory, instruction.label);
                    if (!res) {
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.label + ".\n");
                    }
                }

                break;
            case "BGE":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                if ((Integer)tmpVarVal1.value >= (Integer)tmpVarVal2.value) {
                    boolean res = continueFromLabel(memory, instruction.label);
                    if (!res) {
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.label + ".\n");
                    }
                }

                break;
            case "BLT":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                if ((Integer)tmpVarVal1.value < (Integer)tmpVarVal2.value) {
                    boolean res = continueFromLabel(memory, instruction.label);
                    if (!res) {
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.label + ".\n");
                    }
                }

                break;
            case "BLE":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                if ((Integer)tmpVarVal1.value <= (Integer)tmpVarVal2.value) {
                    boolean res = continueFromLabel(memory, instruction.label);
                    if (!res) {
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.label + ".\n");
                    }
                }
                break;
            case "BEQ":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (instruction.varVal2.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal2 = memory.getVariableByName(instruction.varVal2);
                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal2.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal2.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal2));
                }

                if ((Integer)tmpVarVal1.value == (Integer)tmpVarVal2.value) {
                    boolean res = continueFromLabel(memory, instruction.label);
                    if (!res) {
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.label + ".\n");
                    }
                }
                break;
            case "BRA":
                boolean res = continueFromLabel(memory, instruction.label);
                if (!res) {
                    throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.label + ".\n");
                }

                break;
            case "SND":


                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    }
                } else if (instruction.varVal1.charAt(0) == '"') {
                    tmpVarVal1 = new Variables(Variables.VariableType.STRING, "tmp", instruction.varVal1.substring(1, instruction.varVal1.length() - 1));
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                for (Map.Entry<Integer, Memory> entry : group.entrySet()) { //psaxno na vro an o paraliptis yparxei
                    if (entry.getKey() != (Integer) tmpVarVal1.value) { continue; }

                    //ton vrika, miso na do kai an einai kai sto diko m pc allios ton exo diojei alloy

                    if (entry.getValue().id == -1) {







                        found = true;
                        // prepei na steilo oti exo na steilo sto mhxanhma opoy vrisketai kai to exo ego kanei migrate giati eimai o pateras
                        dataSent = false;
                        for (SNDRCVBlock.Node node: sndrcvBlock.sharedData) {
                            if (node.receiver != (Integer) tmpVarVal1.value) { continue; }
                            if (node.sender != memory.id) { continue; }
                            dataSent = true;
                            if (node.received) {
                                sndrcvBlock.sharedData.remove(node);
                                break;
                            } else {
                                memory.programCounter--;
                                memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                                for (int j = 0; j < memory.programCounter; j++) {
                                    memory.parser.br.readLine();
                                }
                            }
                        }

                        if (!dataSent) {
                            tmpTokens = Parser.splitIntoTokens(instruction.varVal2);
                            for (String token : tmpTokens) {
                                if (token.charAt(0) == '$') { // Assume varVal1 is already in memory
                                    tmpVarVal2 = memory.getVariableByName(token);
                                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + token + ".\n");
                                    }
                                }
                            }

                            sndrcvBlock.sharedData.add(new SNDRCVBlock.Node(memory.id, (Integer) tmpVarVal1.value, memory.parentIP, threadId));
                            for (String token: tmpTokens) {
                                if (token.charAt(0) == '$') {
                                    tmpVarVal2 = memory.getVariableByName(token);
                                } else if (token.charAt(0) == '"') {
                                    tmpVarVal2 = new Variables(Variables.VariableType.STRING, "tmp", token.substring(1, token.length() - 1));
                                } else {
                                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(token));
                                }
                                sndrcvBlock.sharedData.getLast().data.add(tmpVarVal2);
                            }
                            activeTCPsSem.acquire();
                            Memory tmp = group.get((Integer) tmpVarVal1.value);
                            Sender.send("SND", null, null, null, sndrcvBlock.sharedData.getLast(), activeTCPs.get(tmp.currentlyRunningIP).oos);
                            activeTCPsSem.release();
                            memory.programCounter--;
                            memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                            for (int j = 0; j < memory.programCounter; j++) {
                                memory.parser.br.readLine();
                            }
                        }
                        break;
                    }

                    //allios den me noiazei an eimai o pateras, o rcv iparxi edo poy eimai stelno
                    dataSent = false;
                    for (SNDRCVBlock.Node node: sndrcvBlock.sharedData) {
                        if (node.receiver != (Integer) tmpVarVal1.value) { continue; }
                        if (node.sender != memory.id) { continue; }

                        dataSent = true;

                        if (node.received) {
                            sndrcvBlock.sharedData.remove(node);
                            break;
                        } else {
                            memory.programCounter--;
                            memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                            for (int j = 0; j < memory.programCounter; j++) {
                                memory.parser.br.readLine();
                            }
                        }
                    }

                    if (!dataSent) {
                        tmpTokens = Parser.splitIntoTokens(instruction.varVal2);
                        for (String token : tmpTokens) {
                            if (token.charAt(0) == '$') { // Assume varVal1 is already in memory
                                tmpVarVal2 = memory.getVariableByName(token);
                                if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                                    throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + token + ".\n");
                                }
                            }
                        }

                        sndrcvBlock.sharedData.add(new SNDRCVBlock.Node(memory.id, (Integer) tmpVarVal1.value, memory.parentIP, threadId));
                        for (String token : tmpTokens) {
                            if (token.charAt(0) == '$') {
                                tmpVarVal2 = memory.getVariableByName(token);
                            } else if (token.charAt(0) == '"') {
                                tmpVarVal2 = new Variables(Variables.VariableType.STRING, "tmp", token.substring(1, token.length() - 1));
                            } else {
                                tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(token));
                            }
                            sndrcvBlock.sharedData.getLast().data.add(tmpVarVal2);
                        }

                        memory.programCounter--;
                        memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                        for (int j = 0; j < memory.programCounter; j++) {
                            memory.parser.br.readLine();
                        }
                    }
                    found = true;
                    break;
                }

                if (!found) {
                    if (Objects.equals(memory.currentlyRunningIP.getHostAddress(), memory.parentIP.getHostAddress())) {
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\nPossible DeadLock.\n");
                    } else {
                        //prepei na steilo ston patera kai blepoume
                        dataSent = false;
                        for (SNDRCVBlock.Node node: sndrcvBlock.sharedData) {
                            if (node.receiver != (Integer) tmpVarVal1.value) { continue; }
                            if (node.sender != memory.id) { continue; }

                            dataSent = true;

                            if (node.received) {
                                sndrcvBlock.sharedData.remove(node);
                                break;
                            } else {
                                memory.programCounter--;
                                memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                                for (int j = 0; j < memory.programCounter; j++) {
                                    memory.parser.br.readLine();
                                }
                            }
                        }

                        if (!dataSent) {
                            tmpTokens = Parser.splitIntoTokens(instruction.varVal2);
                            for (String token : tmpTokens) {
                                if (token.charAt(0) == '$') { // Assume varVal1 is already in memory
                                    tmpVarVal2 = memory.getVariableByName(token);
                                    if (tmpVarVal2 == null) { // Unfortunately varVal1 not in memory
                                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + token + ".\n");
                                    }
                                }
                            }

                            sndrcvBlock.sharedData.add(new SNDRCVBlock.Node(memory.id, (Integer) tmpVarVal1.value, memory.parentIP, threadId));
                            for (String token: tmpTokens) {
                                if (token.charAt(0) == '$') {
                                    tmpVarVal2 = memory.getVariableByName(token);
                                } else if (token.charAt(0) == '"') {
                                    tmpVarVal2 = new Variables(Variables.VariableType.STRING, "tmp", token.substring(1, token.length() - 1));
                                } else {
                                    tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(token));
                                }
                                sndrcvBlock.sharedData.getLast().data.add(tmpVarVal2);
                            }
                            groupsSem.acquire();

                            Sender.send("SND", null, null, null, sndrcvBlock.sharedData.getLast(), groups.get(memory.parentIP).oos);
                            groupsSem.release();
                            memory.programCounter--;
                            memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                            for (int j = 0; j < memory.programCounter; j++) {
                                memory.parser.br.readLine();
                            }
                        }
                    }
                }

                break;
            case "RCV":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    }
                } else if (instruction.varVal1.charAt(0) == '"') {
                    tmpVarVal1 = new Variables(Variables.VariableType.STRING, "tmp", instruction.varVal1.substring(1, instruction.varVal1.length() - 1));
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                if (Objects.equals(InetAddress.getLocalHost().getHostAddress(), memory.parentIP.getHostAddress())) { // Eimai o pateras
                    for (Map.Entry<Integer, Memory> entry : group.entrySet()) {
                        if (entry.getKey() != (Integer) tmpVarVal1.value) { continue; }
                        found = true;
                    }

                    if (!found) {
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\nPossible DeadLock.\n");
                    }

                    found = false;
                    for (SNDRCVBlock.Node node : sndrcvBlock.sharedData) {
                        if (node.sender != (Integer) tmpVarVal1.value) {
                            continue;
                        }
                        if (node.receiver != memory.id) {
                            continue;
                        }

                        found = true;
                        tmpTokens = Parser.splitIntoTokens(instruction.varVal2);
                        if (node.data.size() != tmpTokens.length) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required " + tmpTokens.length + ", Provided: " + node.data.size() + ".\n");
                        }

                        i = 0;
                        for (Variables var : node.data) {
                            if (tmpTokens[i].charAt(0) == '$') {
                                tmpVarVal2 = memory.getVariableByName(tmpTokens[i]);
                                if (tmpVarVal2 != null) {
                                    tmpVarVal2.type = var.type;
                                    tmpVarVal2.value = var.value;
                                } else {
                                    memory.variables.add(new Variables(var.type, tmpTokens[i], var.value));
                                }
                            } else if (tmpTokens[i].charAt(0) == '"') {
                                tmpVarVal2 = new Variables(Variables.VariableType.STRING, "tmp", tmpTokens[i].substring(1, tmpTokens[i].length() - 1));
                                if (tmpVarVal2.type == var.type) {
                                    if (!((String) tmpVarVal2.value).equals((String) var.value)) {
                                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required: " + tmpVarVal2.value + ", Provided: " + var.value + ".\n");
                                    }
                                } else {
                                    throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: " + tmpVarVal2.type + ", Provided: " + var.type + ".\n");
                                }
                            } else {
                                tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(tmpTokens[i]));
                                if (tmpVarVal2.type == var.type) {
                                    if ((Integer) tmpVarVal2.value != (Integer) var.value) {
                                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required: " + tmpVarVal2.value + ", Provided: " + var.value + ".\n");
                                    }
                                } else {
                                    throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: " + tmpVarVal2.type + ", Provided: " + var.type + ".\n");
                                }
                            }

                            i++;
                        }
                        node.received = true;

                        if (group.get(node.sender).id == -1) {
                            activeTCPsSem.acquire();
                            Sender.send("RCV", null, null, null,node, activeTCPs.get(group.get(node.sender).currentlyRunningIP).oos);
                            activeTCPsSem.release();
                            sndrcvBlock.sharedData.remove(node);
                        }
                        break;
                    }

                    if (!found) {
                        memory.programCounter--;
                        memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                        for (int j = 0; j < memory.programCounter; j++) {
                            memory.parser.br.readLine();
                        }
                    }
                } else { //DEN EIMAI O PATERAS
                    found = false;
                    for (SNDRCVBlock.Node node : sndrcvBlock.sharedData) { // Psaxno na do an exei ertei afto poy thelo na kano receive
                        if (node.sender != (Integer) tmpVarVal1.value) {
                            continue;
                        }

                        if (node.receiver != memory.id) {
                            continue;
                        }

                        found = true;
                        tmpTokens = Parser.splitIntoTokens(instruction.varVal2);
                        if (node.data.size() != tmpTokens.length) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required " + tmpTokens.length + ", Provided: " + node.data.size() + ".\n");
                        }

                        i = 0;
                        for (Variables var : node.data) {
                            if (tmpTokens[i].charAt(0) == '$') {
                                tmpVarVal2 = memory.getVariableByName(tmpTokens[i]);
                                if (tmpVarVal2 != null) {
                                    tmpVarVal2.type = var.type;
                                    tmpVarVal2.value = var.value;
                                } else {
                                    memory.variables.add(new Variables(var.type, tmpTokens[i], var.value));
                                }
                            } else if (tmpTokens[i].charAt(0) == '"') {
                                tmpVarVal2 = new Variables(Variables.VariableType.STRING, "tmp", tmpTokens[i].substring(1, tmpTokens[i].length() - 1));
                                if (tmpVarVal2.type == var.type) {
                                    if (!((String) tmpVarVal2.value).equals((String) var.value)) {
                                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required: " + tmpVarVal2.value + ", Provided: " + var.value + ".\n");
                                    }
                                } else {
                                    throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: " + tmpVarVal2.type + ", Provided: " + var.type + ".\n");
                                }
                            } else {
                                tmpVarVal2 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(tmpTokens[i]));
                                if (tmpVarVal2.type == var.type) {
                                    if ((Integer) tmpVarVal2.value != (Integer) var.value) {
                                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required: " + tmpVarVal2.value + ", Provided: " + var.value + ".\n");
                                    }
                                } else {
                                    throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: " + tmpVarVal2.type + ", Provided: " + var.type + ".\n");
                                }
                            }

                            i++;
                        }

                        node.received = true;

                        Memory tmpSender = group.get(node.sender);
                        if (tmpSender == null) {
                            //o sender de vrethike stelno ston patera na kanei koymanto na ton enimerosei
                            groupsSem.acquire();
                            GroupList fatha = groups.get(node.parentIP);
                            Sender.send("RCV", null, null, null, node, fatha.oos);
                            groupsSem.release();
                            sndrcvBlock.sharedData.remove(node);
                        }
                        break;
                    }

                    if (!found) {
                        memory.programCounter--;
                        memory.parser.br = new BufferedReader(new FileReader(memory.filename));
                        for (int j = 0; j < memory.programCounter; j++) {
                            memory.parser.br.readLine();
                        }
                    }
                }

                break;
            case "SLP":
                if (instruction.varVal1.charAt(0) == '$') { // Assume varVal1 is already in memory
                    tmpVarVal1 = memory.getVariableByName(instruction.varVal1);
                    if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                        throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                    } else {
                        if (tmpVarVal1.type != Variables.VariableType.INT) {
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", Required type: int, Provided: " + tmpVarVal1.type + ".\n");
                        }
                    }
                } else {
                    tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(instruction.varVal1));
                }

                memory.sleeping = true;
                memory.timer = new Timer();
                try {
                    memory.timer.schedule(new WakeUp(memory), (Integer)tmpVarVal1.value * 1000);
                } catch (RuntimeException e) {
                    throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + instruction.varVal1 + ".\n");
                }

                break;
            case "PRN":
                tmpTokens = Parser.splitIntoTokens(instruction.varVal1);
                String toPrint = "";
                for (String str: tmpTokens) {
                    if (str.charAt(0) == '$') { // Assume varVal1 is already in memory
                        tmpVarVal1 = memory.getVariableByName(str);
                        if (tmpVarVal1 == null) { // Unfortunately varVal1 not in memory
                            throw new CustomException("Error in program " + memory.filename + ".\nIn line: " + memory.programCounter + ", cannot resolve symbol: " + str + ".\n");
                        }
                    } else if (str.charAt(0) == '"') {
                        tmpVarVal1 = new Variables(Variables.VariableType.STRING, "tmp", str.substring(1, str.length() - 1));
                    } else {
                        tmpVarVal1 = new Variables(Variables.VariableType.INT, "tmp", Integer.parseInt(str));
                    }

                    toPrint += tmpVarVal1.value;
                }

                if (!Objects.equals(memory.currentlyRunningIP.getHostAddress(), memory.parentIP.getHostAddress())) {
                    groupsSem.acquire();
                    Runtime.GroupList tmp = groups.get(memory.parentIP);
                    tmp.groupListSem.acquire();
                    toPrint = "Thread " + threadId + ": " + toPrint;
                    Sender.send("PRN", null, null, toPrint, null, tmp.oos);
                    tmp.groupListSem.release();
                    groupsSem.release();
                } else {
                    System.out.println("Thread " + threadId + ": " + toPrint);
                }

                break;
            case "RET":
                if (!Objects.equals(memory.currentlyRunningIP.getHostAddress(), memory.parentIP.getHostAddress())) {
                    groupsSem.acquire();
                    Runtime.GroupList tmp = groups.get(memory.parentIP);
                    tmp.groupListSem.acquire();
                    String str = String.valueOf(memory.groupId) + " " + String.valueOf(memory.id);
                    Sender.send("RET", null, null, str, null, tmp.oos);
                    tmp.groupListSem.release();
                    groupsSem.release();
                }

                removeFromGroup(memory.id);

                break;
        }
    }

    public boolean continueFromLabel(Memory memory, String label) throws IOException {

        int tmpProgramCounter = 1;
        String line;
        BufferedReader br = new BufferedReader(new FileReader(memory.filename));
        BufferedReader tmpBr = new BufferedReader(new FileReader(memory.filename));
        String[] tokens;

        while ((line = br.readLine()) != null) {
            line = line.trim();
            tokens = line.split("\\s+");

            if (line.isBlank()) {
                tmpProgramCounter++;
                tmpBr.readLine();
                continue;
            }
            if (line.charAt(0) != '#') {
                tmpProgramCounter++;
                tmpBr.readLine();
                continue;
            }
            if (tokens[0].equals(label)) {
                memory.programCounter = tmpProgramCounter-1;
                memory.parser.br = tmpBr;
                return true;
            }
            tmpProgramCounter++;
            tmpBr.readLine();
        }

        return false;
    }

    public static <K, V> K getKeyByValue(Map<K, V> map, V value) {
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null; // or throw an exception if key is not found
    }

    public int runningProcesses(Map<Integer, Memory> map) {
        int processCnt = 0;

        for (Map.Entry<Integer, Memory> entry : map.entrySet()) {
            if (entry.getValue().id != -1) {
                processCnt++;
            }
        }
        return processCnt; // or throw an exception if key is not found
    }
}
