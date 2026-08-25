package example;

import java.util.List;
import java.util.Arrays;

public class ParameterObjectCallers {
    public void callTopLevel() {
        new ParameterObjectTopLevelSamples().createInvoice("Alice", "USD", 30);
        new ParameterObjectTopLevelSamples().createInvoice("Bob", "EUR", 15);
    }

    public void callInner() {
        new ParameterObjectInnerSamples().createInvoice("Bob", "EUR", 15);
        new ParameterObjectInnerSamples().createInvoice("Carol", "JPY", 7);
    }

    public void callExisting() {
        new ParameterObjectExistingSamples().createInvoice("JPY", 7);
        new ParameterObjectExistingSamples().createInvoice("USD", 30);
    }

    public void callGeneric() {
        new ParameterObjectTopLevelSamples().handleGeneric("hello", " world");
        new ParameterObjectTopLevelSamples().handleList(Arrays.asList("a", "b"), " pre");
    }
}
