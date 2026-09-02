package example;

public class ReplaceInheritanceSamples extends ReplaceInheritanceBase {
    public String describe() {
        return "derived:" + label();
    }

    public int answer() {
        return value();
    }
}
