package example;

public class ParameterObjectCallers {
    public void callTopLevel() {
        new ParameterObjectTopLevelSamples().createInvoice("Alice", "USD", 30, false);
    }
    public void callInner() {
        new ParameterObjectInnerSamples().createInvoice("Bob", "EUR", 15, true);
    }
    public void callExisting() {
        new ParameterObjectExistingSamples().createInvoice("JPY", 7);
    }
}
