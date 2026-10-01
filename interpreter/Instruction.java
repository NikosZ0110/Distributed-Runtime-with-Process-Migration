package interpreter;

class Instruction {
    String operation;
    String varName;
    String varVal1;
    String varVal2;
    String label;

    Instruction() {
        operation = null;
        varName = null;
        varVal1 = null;
        varVal2 = null;
        label = null;
    }
}