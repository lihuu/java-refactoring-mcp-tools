package example;

public class PushDownCallers {
    public void useA(PushDownSubA a) {
        a.handle("y");
    }
    public void useB(PushDownSubB b) {
        b.handle("z");
    }
}
