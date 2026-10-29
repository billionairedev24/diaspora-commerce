package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import org.junit.jupiter.api.Test;

/**
 * HARD RULE (ADR 0006, S320): AfroTransact store credit and gift card value can only buy products and services. It
 * can never become cash: no cash-out, payout, withdrawal, transfer, conversion or refund to a card, in the API, the
 * database, the services, the staff console or the apps.
 *
 * <p>This test fails the build when such a path appears anywhere in the product. It looks at the names a path must
 * have to exist: the HTTP paths and operation ids of every contract, the tables and columns of every migration, and
 * the file and type names of every service and screen. The behaviour (a credit refund never reaches Stripe) is proved
 * by {@code CreditLedgerIT}; this guard is the net under it that catches a new door.
 */
class CreditNeverBecomesCashTest {

    /** Something that is, or holds, store credit or gift card value. */
    private static final Pattern CREDIT = Pattern.compile(
            "(?i)(store[-_ ]?credit|afrotransact[-_ ]?credit|credit|gift[-_ ]?cards?|giftcards?)");

    /** A way for money to leave AfroTransact or reach a card, a bank or another person. */
    private static final Pattern CASH_OUT = Pattern.compile("(?i)(cash[-_ ]?out|cashout|encash|withdraw|pay[-_ ]?outs?"
            + "|transfers?|convert|to[-_ ]?(the[-_ ]?)?(card|bank|cash)|card[-_ ]?refund|refund[-_ ]?(to[-_ ]?)?card"
            + "|send[-_ ]?money|gift[-_ ]?credit)");

    /**
     * Names that mention both words and are not credit leaving: each entry says why. Add here only after a human
     * confirms the thing is not store credit becoming cash.
     */
    private static final Set<String> ALLOWED = Set.of(
            // The staff "transfer" of a seller's own billing credit is not shopper credit (seller billing, not S320).
            // (none today)
    );

    private static final Set<String> SKIPPED_DIRS = Set.of("node_modules", ".next", "target", ".git", "dist", "build",
            ".turbo", "coverage", "vendor");

    @Test
    void theCreditLedgerCanOnlyAddSpendTakeBackAndReadTheBalance() {
        Set<String> methods = Arrays.stream(CreditLedger.class.getDeclaredMethods())
                .map(m -> m.getName()).collect(Collectors.toSet());
        assertThat(methods).as("a new way to move credit needs the same review as a new way to move cash")
                .containsExactlyInAnyOrder("add", "spend", "take", "balance");
        assertThat(Arrays.stream(CreditKind.values()).map(Enum::name))
                .containsExactlyInAnyOrder("GRANT", "SPEND", "REFUND", "ADJUSTMENT");
        assertThat(Arrays.stream(CreditSource.values()).map(Enum::name))
                .containsExactlyInAnyOrder("REFERRAL", "GIFT_CARD", "MEMBERSHIP", "REFUND", "STAFF", "PROMO", "ORDER");
    }

    @Test
    void noContractHasAPathOrOperationThatMovesCreditOut() throws IOException {
        Path root = repoRoot();
        List<String> found = new ArrayList<>();
        List<Path> contracts = new ArrayList<>();
        walk(root.resolve("contracts/openapi"), p -> p.toString().endsWith(".yaml"), contracts::add);
        walk(root.resolve("go/services"), p -> p.toString().endsWith(".yaml") && p.toString().contains("/api/"),
                contracts::add);
        assertThat(contracts).as("the contracts were found").hasSizeGreaterThan(10);
        Pattern name = Pattern.compile("^\\s*(?:(/[^\\s:]*):|operationId:\\s*(\\S+)|summary:\\s*(.+))\\s*$");
        for (Path file : contracts) {
            for (String line : Files.readAllLines(file)) {
                Matcher m = name.matcher(line);
                if (m.matches()) {
                    String token = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                    flag(found, root, file, token);
                }
            }
        }
        assertThat(found).as("contract paths or operations that move credit out").isEmpty();
    }

    @Test
    void noMigrationHasATableOrColumnThatHoldsCreditForPayoutOrTransfer() throws IOException {
        Path root = repoRoot();
        List<String> found = new ArrayList<>();
        List<Path> migrations = new ArrayList<>();
        walk(root.resolve("java/services"), p -> p.toString().endsWith(".sql") && p.toString().contains("migration"),
                migrations::add);
        walk(root.resolve("go/services"), p -> p.toString().endsWith(".sql") && p.toString().contains("migration"),
                migrations::add);
        assertThat(migrations).as("the migrations were found").hasSizeGreaterThan(10);
        Pattern ident = Pattern.compile("(?i)(?:create\\s+table(?:\\s+if\\s+not\\s+exists)?|add\\s+column"
                + "(?:\\s+if\\s+not\\s+exists)?|create\\s+(?:unique\\s+)?index(?:\\s+if\\s+not\\s+exists)?)\\s+"
                + "([a-z0-9_.\"]+)");
        for (Path file : migrations) {
            Matcher m = ident.matcher(Files.readString(file));
            while (m.find()) {
                flag(found, root, file, m.group(1));
            }
        }
        // credit_entry has exactly the kinds that move credit within AfroTransact
        String v20 = Files.readString(root.resolve(
                "java/services/payments/src/main/resources/db/migration/V20__credit_ledger_and_gift_cards.sql"));
        assertThat(v20).contains("kind in ('GRANT', 'SPEND', 'REFUND', 'ADJUSTMENT')");
        assertThat(found).as("tables or columns that move credit out").isEmpty();
    }

    @Test
    void noServiceScreenOrAppHasAFileThatMovesCreditOut() throws IOException {
        Path root = repoRoot();
        List<String> found = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        for (String dir : List.of("java/services", "java/libs", "go", "web/apps", "web/packages", "mobile/apps")) {
            walk(root.resolve(dir), p -> {
                String s = p.toString();
                return s.endsWith(".java") || s.endsWith(".go") || s.endsWith(".ts") || s.endsWith(".tsx");
            }, sources::add);
        }
        assertThat(sources).as("the sources were found").hasSizeGreaterThan(100);
        for (Path file : sources) {
            flag(found, root, file, file.getFileName().toString());
        }
        assertThat(found).as("files named for moving credit out").isEmpty();
    }

    @Test
    void noPayoutOrStripeTransferCodeReadsTheCreditLedger() throws IOException {
        Path root = repoRoot();
        Path main = root.resolve("java/services/payments/src/main/java/com/afrotransact/payments");
        List<String> found = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        walk(main, p -> p.toString().endsWith(".java"), sources::add);
        assertThat(sources).isNotEmpty();
        Pattern credit = Pattern.compile("\\bCreditLedger\\b|\\bCreditEntry\\b|\\bCreditLedgerRepository\\b");
        Pattern money = Pattern.compile("\\b(?:createTransfer|reverseTransfer|createPayout|transfers?\\()");
        for (Path file : sources) {
            String text = Files.readString(file);
            String name = file.getFileName().toString();
            boolean payoutSide = name.contains("Payout") || name.contains("Settlement") || name.contains("Transfer");
            if (payoutSide && credit.matcher(text).find()) {
                found.add(root.relativize(file) + " is payout code and reads the credit ledger");
            }
            // PaymentServiceImpl pays sellers for goods they sold (a transfer on capture) and takes credit as the
            // shopper's payment for them: that is credit buying products, which CreditLedgerIT proves end to end.
            if (!name.equals("PaymentServiceImpl.java") && credit.matcher(text).find()
                    && money.matcher(text).find()) {
                found.add(root.relativize(file) + " moves credit and calls a Stripe transfer or payout");
            }
        }
        assertThat(found).isEmpty();
    }

    private static void flag(List<String> found, Path root, Path file, String name) {
        if (CREDIT.matcher(name).find() && CASH_OUT.matcher(name).find()
                && !ALLOWED.contains(root.relativize(file) + "#" + name)) {
            found.add(root.relativize(file) + " -> " + name);
        }
    }

    private static void walk(Path dir, Predicate<Path> keep, Consumer<Path> out)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> StreamSupportDirs.clean(dir, p))
                    .filter(keep)
                    .forEach(out);
        }
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("CLAUDE.md"))) {
            p = p.getParent();
        }
        assertThat(p).as("the repository root (CLAUDE.md) above the working directory").isNotNull();
        return p;
    }

    /** Skips build output and dependencies. */
    private static final class StreamSupportDirs {
        private StreamSupportDirs() {
        }

        static boolean clean(Path base, Path file) {
            for (Path part : base.relativize(file)) {
                if (SKIPPED_DIRS.contains(part.toString())) {
                    return false;
                }
            }
            return true;
        }
    }
}
