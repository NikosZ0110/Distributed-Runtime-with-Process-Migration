package interpreter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;

public class Memory {
    public int getGroupId() { return groupId; }
    public int getProcessId() { return id; }

    public int groupId;
    public int id;
    public String filename;

    public List<Variables> variables;

    public int programCounter;
    public Parser parser;

    public Timer timer;
    public boolean sleeping;

    public InetAddress parentIP;
    public InetAddress currentlyRunningIP;

    public Memory(int groupId, int id, String[] argv) throws UnknownHostException {
        this.groupId = groupId;
        this.id = id;
        filename = argv[0];
        programCounter = 1;
        variables = new ArrayList<>();
        sleeping = false;
        parentIP = InetAddress.getByName(InetAddress.getLocalHost().getHostAddress());
        currentlyRunningIP = parentIP;

        int i = 0;
        for (String str: argv) {
            String name = "$argv" + i;
            try {
                int tmpInt = Integer.parseInt(str);
                addVariable(Variables.VariableType.INT, name, tmpInt);
            } catch (NumberFormatException e) {
                addVariable(Variables.VariableType.STRING, name, str);
            }

            i++;
        }

        addVariable(Variables.VariableType.INT, "$argc", argv.length);
    }

    public Memory(MigrationInfoData object) throws IOException, CustomException {
        this.groupId = object.groupID;
        this.id = object.processID;
        this.filename = object.filename;
        this.variables = object.variables;
        this.programCounter = object.programCounter;
        this.parentIP = object.parentIP;
        currentlyRunningIP = InetAddress.getByName(InetAddress.getLocalHost().getHostAddress());
        parser = new Parser(this);
        continueFromLine(programCounter);
        sleeping = false;
    }

    void addVariable(Variables.VariableType type, String varName, Object value) {
        Variables newVariable = new Variables(type, varName, value);
        variables.add(newVariable);
    }

    public Variables getVariableByName(String varName) {
        for (Variables var: variables) {
            if (var.varName.equals(varName)) { return var; }
        }

        return null;
    }

    void continueFromLine(int programCounter) throws IOException {
        for (int i = 1; i < programCounter; i++) {
            parser.br.readLine();
        }
        this.programCounter = programCounter;
    }
}
