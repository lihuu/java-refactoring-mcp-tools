package example;

import java.util.List;
import java.util.Arrays;

public class ParameterObjectCallers {
    public void callTopLevel() {
        new ParameterObjectTopLevelSamples().createInvoice("Alice", "USD", 30, false);
        new ParameterObjectTopLevelSamples().createInvoice("Bob", "EUR", 15, true);
    }

    public void callInner() {
        new ParameterObjectInnerSamples().createInvoice("Bob", "EUR", 15, true);
        new ParameterObjectInnerSamples().createInvoice("Carol", "JPY", 7, false);
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
