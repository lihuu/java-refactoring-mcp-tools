package example;

import java.util.function.BiFunction;

public class ConversionCallers {
    public int directDiscount() {
        ConversionSamples.Customer customer = new ConversionSamples.Customer(10);
        return ConversionSamples.discount(customer, 100);
    }

    public BiFunction<ConversionSamples.Customer, Integer, Integer> discountRef() {
        return ConversionSamples::discount;
    }

    public String callNormalize() {
        return ConversionSamples.normalize("  HELLO ");
    }
}
