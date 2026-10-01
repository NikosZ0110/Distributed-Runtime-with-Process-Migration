package runtime;

public class Block {
    public final Object lock = new Object();

    public void block() throws InterruptedException {
        synchronized (lock) {
            lock.wait();
        }
    }

    public void unblock() {
        synchronized (lock) {
            lock.notify();
        }
    }
}
