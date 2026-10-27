import java.nio.file.*;

/** Reuses a closed seed database by copying it into a new, isolated output folder. */
public final class SqliteQueueLimitTest {
    public static void main(String[] args) throws Exception {
        var test = new SqliteLoadTest(Path.of(args[1]), true);
        try {
            Files.copy(Path.of(args[0]), test.directory.resolve("bancinematic.db"), StandardCopyOption.REPLACE_EXISTING);
            test.burst("flood_650deep_history_then_100joins", 100, 650, true);
        } finally {
            test.clock.shutdownNow();
            test.worker.close();
            test.db.close();
        }
    }
}
