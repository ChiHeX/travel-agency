"""Verify every project SQL file against an isolated database on a local MySQL.

Uses the mysql CLI and Python standard library. Credentials come from MYSQL_PWD
or the client's usual option file; no passwords are included in command arguments.
Only randomly named databases created by this invocation are removed.
"""

import argparse
from contextlib import contextmanager
from pathlib import Path
import re
import subprocess
import uuid


SQL_DIR = Path(__file__).resolve().parent


class Verifier:
    def __init__(self, args):
        self.command = [args.mysql, "--protocol=TCP", f"--host={args.host}",
                        f"--port={args.port}", f"--user={args.user}",
                        "--batch", "--skip-column-names", "--default-character-set=latin1"]

    def execute(self, sql, database=None, expect_error=False):
        command = self.command + ([f"--database={database}"] if database else [])
        result = subprocess.run(command, input=sql.encode("utf-8"), capture_output=True)
        output = result.stdout.decode("utf-8", errors="replace").strip()
        error = result.stderr.decode("utf-8", errors="replace").strip()
        if expect_error:
            assert result.returncode != 0, "Invalid accommodation was unexpectedly accepted"
            assert "3819" in error, f"Expected a CHECK violation, got: {error}"
        elif result.returncode:
            raise RuntimeError(f"mysql failed for {database or 'server'}: {error}")
        return output

    @contextmanager
    def database(self):
        name = "travel_sql57_test_" + uuid.uuid4().hex[:12]
        self.execute(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        try:
            yield name
        finally:
            assert re.fullmatch(r"travel_sql57_test_[0-9a-f]{12}", name)
            self.execute(f"DROP DATABASE `{name}`;")

    def script(self, path, database, prefix=""):
        sql = path.read_text(encoding="utf-8-sig")
        # Only redirect this project's explicit database selection statements.
        # Do not rewrite names or values in the demo business data.
        sql = re.sub(r"(?im)^(CREATE DATABASE IF NOT EXISTS|USE) travel_agency\b",
                     lambda match: match.group(1) + f" `{database}`", sql)
        return self.execute(prefix + sql, database)

    def counts(self, database):
        names = self.execute("SHOW TABLES;", database).splitlines()
        return {name: self.execute(f"SELECT COUNT(*) FROM `{name}`;", database) for name in names}


def verify_all_files(verifier, version_number):
    migrations = sorted((SQL_DIR / "migrations").glob("*.sql"))
    expected = {SQL_DIR / "schema.sql", SQL_DIR / "test-data.sql", *migrations}
    assert set(SQL_DIR.rglob("*.sql")) == expected, "New SQL files must be added to the verification flow"
    with verifier.database() as database:
        verifier.script(SQL_DIR / "schema.sql", database)
        verifier.script(SQL_DIR / "test-data.sql", database)
        assert verifier.execute("SET NAMES utf8mb4; SELECT nickname FROM sys_user WHERE username='admin';",
                                database) == "系统管理员", "Chinese seed text was corrupted"
        assert verifier.execute("SELECT COUNT(*) FROM hotel WHERE facilities IS NOT NULL AND NOT JSON_VALID(facilities);",
                                database) == "0"
        first_counts = verifier.counts(database)
        verifier.script(SQL_DIR / "schema.sql", database)
        assert verifier.counts(database) == first_counts, "Repeated baseline changed data"

        # Reproduce the known latin1-import damage, including an interrupted
        # attempt that left a temporary table on the same connection.
        verifier.execute("""
            SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
            UPDATE attraction SET intro=CONVERT(CONVERT(CAST(
                _utf8mb4'示例景点资料，用于地图与行程演示。' AS BINARY) USING latin1) USING utf8mb4)
            WHERE name='大理古城';
        """, database)
        verifier.script(SQL_DIR / "migrations" / "002-repair-demo-text-encoding.sql", database,
                        prefix="CREATE TEMPORARY TABLE demo_text_encoding_fix (stale INT);\n")
        assert verifier.execute("SET NAMES utf8mb4; SELECT intro FROM attraction WHERE name='大理古城' LIMIT 1;",
                                database) == "示例景点资料，用于地图与行程演示。", "Encoding repair did not recover"

        # Unlike demo seeds, migrations must be safe to apply repeatedly.
        for path in migrations:
            verifier.script(path, database)
        migrated_counts = verifier.counts(database)
        for path in migrations:
            verifier.script(path, database)
        assert verifier.counts(database) == migrated_counts, "Repeated migrations duplicated/deleted rows"
        assert verifier.execute("SELECT COUNT(*) FROM information_schema.TABLES "
                                "WHERE TABLE_SCHEMA=DATABASE() AND "
                                "(ROW_FORMAT <> 'Dynamic' OR TABLE_COLLATION <> 'utf8mb4_unicode_ci');",
                                database) == "0", "Unexpected row format or collation"
        checks = verifier.execute("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                                  "WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_day_accommodation';",
                                  database)
        assert checks == ("1" if version_number >= 80016 else "0"), "Incorrect CHECK version gate"
    print(f"PASS: all {len(expected)} SQL files, initialization, UTF-8, JSON, repeat baseline and migrations")


def verify_old_schema_migrations(verifier, version_number):
    with verifier.database() as database:
        verifier.execute("""
            CREATE TABLE travel_order (id BIGINT PRIMARY KEY, adult_count INT NOT NULL);
            CREATE TABLE order_traveler (
                id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, traveler_id BIGINT,
                KEY idx_order (order_id)
            );
            INSERT INTO travel_order VALUES (1,2), (2,0), (3,5), (4,1);
            INSERT INTO order_traveler VALUES
                (10,1,NULL), (30,1,NULL), (90,1,NULL), (20,2,NULL),
                (40,3,NULL), (50,4,NULL), (80,4,NULL);
        """, database)
        migration = SQL_DIR / "migrations" / "006-add-order-traveler-type.sql"
        verifier.script(migration, database)
        assert verifier.execute("SELECT GROUP_CONCAT(CONCAT(id,':',traveler_type) ORDER BY id) FROM order_traveler;",
                                database) == "10:ADULT,20:CHILD,30:ADULT,40:ADULT,50:ADULT,80:CHILD,90:CHILD"
        verifier.execute("UPDATE order_traveler SET traveler_type='CHILD' WHERE id=10;", database)
        verifier.script(migration, database)
        expected = "10:CHILD,20:CHILD,30:ADULT,40:ADULT,50:ADULT,80:CHILD,90:CHILD"
        assert verifier.execute("SELECT GROUP_CONCAT(CONCAT(id,':',traveler_type) ORDER BY id) FROM order_traveler;",
                                database) == expected
        verifier.script(migration, database)
        assert verifier.execute("SELECT GROUP_CONCAT(CONCAT(id,':',traveler_type) ORDER BY id) FROM order_traveler;",
                                database) == expected

        # This is an actual pre-010 schema, so ADD COLUMN branches execute.
        verifier.execute("""
            CREATE TABLE hotel (
                id BIGINT PRIMARY KEY, name VARCHAR(128), address VARCHAR(255),
                contact_phone VARCHAR(20), longitude DECIMAL(10,7), latitude DECIMAL(10,7),
                intro TEXT, data_source VARCHAR(500), status TINYINT
            ) ENGINE=InnoDB;
            CREATE TABLE route_itinerary_day (id BIGINT PRIMARY KEY, hotel_id BIGINT) ENGINE=InnoDB;
            INSERT INTO hotel (id,name,status) VALUES (1,'old-hotel',1);
            INSERT INTO route_itinerary_day VALUES (1,1),(2,NULL);
        """, database)
        migration = SQL_DIR / "migrations" / "010-add-hotel-accommodation.sql"
        verifier.script(migration, database)
        assert verifier.execute("SELECT GROUP_CONCAT(CONCAT(id,':',accommodation_type) ORDER BY id) FROM route_itinerary_day;",
                                database) == "1:HOTEL,2:PENDING"
        verifier.script(migration, database)
        valid = """
            INSERT INTO route_itinerary_day (id,accommodation_type,hotel_id,accommodation_standard)
            VALUES (3,'HOTEL',1,NULL),(4,'STANDARD',NULL,'twin room'),
                   (5,'NONE',NULL,NULL),(6,'PENDING',NULL,NULL);
        """
        verifier.execute(valid, database)
        invalid = "INSERT INTO route_itinerary_day (id,hotel_id) VALUES (7,1);"
        verifier.execute(invalid, database, expect_error=version_number >= 80016)
    print("PASS: old-schema 006/010 upgrades, traveler boundaries, idempotence and CHECK behavior")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", default="mysql", help="Path to mysql client executable")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    args = parser.parse_args()
    if args.host not in ("127.0.0.1", "localhost", "::1"):
        parser.error("Use an isolated local MySQL instance, not a remote/production server")
    verifier = Verifier(args)
    version = verifier.execute("SELECT VERSION();")
    parts = re.match(r"(\d+)\.(\d+)\.(\d+)", version)
    assert parts, f"Cannot parse MySQL version: {version}"
    major, minor, patch = map(int, parts.groups())
    version_number = major * 10000 + minor * 100 + patch
    assert version_number >= 50709, "MySQL 5.7.9 or later is required (JSON and InnoDB)"
    print(f"Testing MySQL {version}; randomly named test databases are removed on exit")
    verify_all_files(verifier, version_number)
    verify_old_schema_migrations(verifier, version_number)


if __name__ == "__main__":
    main()
