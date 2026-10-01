package interpreter;

import java.io.Serializable;

public class Variables implements Serializable {
    private static final long serialVersionUID = 1L;
    enum VariableType {
        INT, STRING
    }

    VariableType type;
    public Object value;
    final String varName;

    Variables(VariableType type, String varName, Object value) {
        this.type = type;
        this.varName = varName;
        this.value = value;
    }
}