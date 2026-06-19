public class RenameLocal {
    void m() {
        int <caret>userCount = 0;
        userCount++;
        System.out.println(userCount);
    }
}
