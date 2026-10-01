package interpreter;

import java.util.TimerTask;

class WakeUp extends TimerTask {
    Memory memory;

    WakeUp(Memory memory) {
        this.memory = memory;
    }

    @Override
    public void run() {
        memory.sleeping = false;
        memory.timer.cancel();
    }
}