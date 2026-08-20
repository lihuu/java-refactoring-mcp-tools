package example;

public class PushDownCallers {
    public void use(PushDownSuper s) {
        s.handle("x");
    }
    public void useA(PushDownSubA a) {
        a.handle("y");
    }
}
