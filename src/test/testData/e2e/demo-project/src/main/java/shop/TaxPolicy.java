package shop;

public class TaxPolicy {

    public double rateFor(String countryCode) {
        if (countryCode.equals("DE")) {
            return 0.19;
        }
        if (countryCode.equals("US")) {
            return 0.07;
        }
        return 0.20;
    }
}
