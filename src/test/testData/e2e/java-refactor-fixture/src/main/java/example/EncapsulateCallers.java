package example;

public class EncapsulateCallers {
    public int readAmount(EncapsulateSamples samples) {
        return samples.amount;
    }

    public void writeStatus(EncapsulateSamples samples, String value) {
        samples.status = value;
    }

    public String readStatus(EncapsulateSamples samples) {
        return samples.status;
    }

    public void writeAmount(EncapsulateSamples samples, int value) {
        samples.amount = value;
    }
}
