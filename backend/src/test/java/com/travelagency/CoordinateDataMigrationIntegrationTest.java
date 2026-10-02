package com.travelagency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sql/migrations/009-harden-coordinate-data.sql} 的行为测试（需要数据库，
 * {@code TRAVEL_MYSQL_TEST=true}）。
 *
 * <p>迁移脚本是这个仓库里唯一"直接改存量数据"的代码，但 CI 只导入 {@code schema.sql} 与
 * {@code test-data.sql}，从不执行迁移目录 —— 脚本写错了没有任何自动化会报出来。这里把脚本本身
 * 读进来、在测试事务内执行（结束后回滚），钉住三条约定：</p>
 * <ol>
 *   <li><b>只清"半截坐标"</b>：只填了经度或只填了纬度的行两列都清成 NULL；成对的行、
 *       两列都为空的行都不受影响；</li>
 *   <li><b>不写入任何坐标值</b>：脚本里所有对 {@code longitude} / {@code latitude} 的赋值都必须是
 *       {@code NULL}。早先的写法会按名称给演示酒店补坐标，而 {@code hotel.name} 没有唯一约束 ——
 *       同名记录不止一条时，每执行一次就顺着 {@code ORDER BY id LIMIT 1} 补下一条，
 *       既不幂等，也无法保证补的是演示线路真正引用的那家酒店；</li>
 *   <li><b>幂等</b>：第二次执行后的数据与第一次执行后完全一致。</li>
 * </ol>
 *
 * <p>脚本里的 {@code SET NAMES} / {@code USE} 属于命令行环境语句，本测试跳过：测试跑在测试库
 * 自己的事务里，{@code USE travel_agency} 会把这个连接切到另一个库上去。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=integration-test-jwt-secret-at-least-32-bytes-long")
@Transactional
@EnabledIfEnvironmentVariable(named = "TRAVEL_MYSQL_TEST", matches = "true")
class CoordinateDataMigrationIntegrationTest {

    /** Maven 的测试工作目录是 backend/，因此脚本在上一级；从仓库根目录运行时也能找到。 */
    private static final List<Path> MIGRATION_CANDIDATES = List.of(
            Path.of("..", "sql", "migrations", "009-harden-coordinate-data.sql"),
            Path.of("sql", "migrations", "009-harden-coordinate-data.sql"));

    private static final Pattern COORDINATE_ASSIGNMENT =
            Pattern.compile("(?i)set\\s+(longitude|latitude)\\s*=\\s*(\\S+)");

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("只清半截坐标：成对与两列皆空的行不受影响")
    void clearsOnlyHalfCoordinates() throws IOException {
        long onlyLongitude = insertAttraction("迁移校验-只有经度", 120.1000000, null);
        long onlyLatitude = insertAttraction("迁移校验-只有纬度", null, 30.2000000);
        long completePair = insertAttraction("迁移校验-成对", 120.1000000, 30.2000000);
        long bothEmpty = insertAttraction("迁移校验-都为空", null, null);
        long halfHotel = insertHotel("迁移校验-半截酒店", 100.1000000, null);
        long halfItem = insertItineraryItem(null, 25.3000000);

        runMigration();

        assertCoordinates("attraction", onlyLongitude, null, null, "只填经度的景点两列都要清空");
        assertCoordinates("attraction", onlyLatitude, null, null, "只填纬度的景点两列都要清空");
        assertCoordinates("attraction", completePair, 120.1000000, 30.2000000, "成对的坐标不能被改动");
        assertCoordinates("attraction", bothEmpty, null, null, "两列都为空的记录保持为空");
        assertCoordinates("hotel", halfHotel, null, null, "只填经度的酒店两列都要清空");
        assertCoordinates("route_itinerary_item", halfItem, null, null, "行程项目的半截坐标同样要清空");
    }

    @Test
    @DisplayName("同名酒店不止一条时也不补坐标，重复执行不修改任何行")
    void neverWritesCoordinatesAndStaysIdempotentWithDuplicateNames() throws IOException {
        // hotel.name 没有唯一约束：同名且都缺坐标的"彩云之南演示酒店"可能出现多条（评审报告的场景）。
        long firstDemo = insertHotel("彩云之南演示酒店", null, null);
        long secondDemo = insertHotel("彩云之南演示酒店", null, null);
        long halfHotel = insertHotel("迁移校验-重复执行", 100.1000000, null);

        runMigration();
        Map<String, String> afterFirstRun = coordinateSnapshot(firstDemo, secondDemo, halfHotel);

        // 关键断言：任何一条同名酒店都不会被"补"上坐标，只有半截行被清空。
        assertCoordinates("hotel", firstDemo, null, null, "同名酒店不能被自动补坐标");
        assertCoordinates("hotel", secondDemo, null, null, "同名酒店不能被自动补坐标");
        assertCoordinates("hotel", halfHotel, null, null, "半截坐标要被清空");

        runMigration();
        assertEquals(afterFirstRun, coordinateSnapshot(firstDemo, secondDemo, halfHotel),
                "第二次执行不得再修改任何行（幂等）");
    }

    @Test
    @DisplayName("脚本本身只清空坐标，不写入任何坐标值")
    void migrationOnlyClearsCoordinates() throws IOException {
        List<String> assignedValues = new ArrayList<>();
        for (String statement : migrationStatements()) {
            Matcher matcher = COORDINATE_ASSIGNMENT.matcher(statement);
            while (matcher.find()) {
                assignedValues.add(matcher.group(2));
            }
        }
        assertTrue(!assignedValues.isEmpty(),
                "脚本里应当存在坐标赋值语句；解析结果为空说明脚本结构已变，本用例需要同步调整");
        for (String assigned : assignedValues) {
            assertEquals("NULL", assigned.toUpperCase(Locale.ROOT),
                    "迁移脚本只能把坐标清成 NULL，不能写入具体坐标值（实际写入：" + assigned + "）");
        }
    }

    // ------------------------------------------------------------------
    // 辅助：读取脚本、执行脚本、造数据与断言
    // ------------------------------------------------------------------

    /**
     * 读出迁移脚本里的可执行语句。
     *
     * <p>先去掉 {@code --} 行注释再按分号切分：这样注释里出现分号也不会把语句切错。
     * 跳过 {@code SET NAMES} / {@code USE} 这类命令行环境语句（见类注释）。</p>
     */
    private static List<String> migrationStatements() throws IOException {
        Path script = MIGRATION_CANDIDATES.stream().filter(Files::exists).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到迁移脚本，尝试过的路径：" + MIGRATION_CANDIDATES));
        String withoutComments = Arrays.stream(Files.readString(script, StandardCharsets.UTF_8).split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        List<String> statements = new ArrayList<>();
        for (String candidate : withoutComments.split(";")) {
            String statement = candidate.trim();
            if (statement.isEmpty()) {
                continue;
            }
            String upper = statement.toUpperCase(Locale.ROOT);
            if (upper.startsWith("USE ") || upper.startsWith("SET NAMES")) {
                continue;
            }
            statements.add(statement);
        }
        assertTrue(statements.size() >= 6,
                "三张表 × 两个清理方向应当至少有 6 条语句，实际只解析出 " + statements.size() + " 条");
        return statements;
    }

    private void runMigration() throws IOException {
        for (String statement : migrationStatements()) {
            jdbc.execute(statement);
        }
    }

    private long insertAttraction(String name, Double longitude, Double latitude) {
        jdbc.update("INSERT INTO attraction (name, city, data_source, status, longitude, latitude) "
                + "VALUES (?, '迁移校验', '仅测试用数据', 1, ?, ?)", name, longitude, latitude);
        return jdbc.queryForObject("SELECT id FROM attraction WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, name);
    }

    private long insertHotel(String name, Double longitude, Double latitude) {
        jdbc.update("INSERT INTO hotel (name, address, contact_phone, data_source, status, longitude, latitude) "
                + "VALUES (?, '迁移校验地址', '000-00000000', '仅测试用数据', 1, ?, ?)", name, longitude, latitude);
        return jdbc.queryForObject("SELECT id FROM hotel WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, name);
    }

    /** 造一条挂在新建线路下的行程项目，用于覆盖 {@code route_itinerary_item} 这条清理分支。 */
    private long insertItineraryItem(Double longitude, Double latitude) {
        jdbc.update("INSERT INTO travel_route (name, departure_city, destination, duration_days, status) "
                + "VALUES ('迁移校验线路', '迁移校验', '迁移校验', 1, 'DRAFT')");
        Long routeId = jdbc.queryForObject(
                "SELECT id FROM travel_route WHERE name = '迁移校验线路' ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO route_itinerary_day (route_id, day_number, title) VALUES (?, 1, '迁移校验第一天')",
                routeId);
        Long dayId = jdbc.queryForObject(
                "SELECT id FROM route_itinerary_day WHERE route_id = ? ORDER BY id DESC LIMIT 1", Long.class, routeId);
        jdbc.update("INSERT INTO route_itinerary_item (day_id, sort_no, item_type, name, longitude, latitude) "
                + "VALUES (?, 1, 'OTHER', '迁移校验坐标点', ?, ?)", dayId, longitude, latitude);
        return jdbc.queryForObject("SELECT id FROM route_itinerary_item WHERE day_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, dayId);
    }

    private void assertCoordinates(String table, long id, Double expectedLongitude, Double expectedLatitude,
                                   String message) {
        assertEquals(expectedLongitude, coordinateOf(table, id, "longitude"), message + "（经度）");
        assertEquals(expectedLatitude, coordinateOf(table, id, "latitude"), message + "（纬度）");
    }

    /** 取某行的某个坐标列；{@code table} / {@code column} 都由测试自身给出，不接受外部输入。 */
    private Double coordinateOf(String table, long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Double.class, id);
    }

    private Map<String, String> coordinateSnapshot(long... hotelIds) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (long id : hotelIds) {
            snapshot.put("hotel#" + id,
                    coordinateOf("hotel", id, "longitude") + "," + coordinateOf("hotel", id, "latitude"));
        }
        return snapshot;
    }
}
