package example;

public final class MakeStaticCallers {
    public int netTotal() {
        OrderService service = new OrderService(20);
        return service.netAmount(100);
    }

    public String formattedLine() {
        OrderService service = new OrderService(20);
        OrderService.LineFormatter formatter = service.new LineFormatter("total:");
        return formatter.render(100);
    }
}
