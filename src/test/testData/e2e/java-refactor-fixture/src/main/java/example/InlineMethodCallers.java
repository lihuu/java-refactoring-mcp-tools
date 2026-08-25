package example;

public final class InlineMethodCallers {
    public int total(int amount) {
        return InlineMethodSamples.addTax(amount) + InlineMethodSamples.addTax(10);
    }
}
