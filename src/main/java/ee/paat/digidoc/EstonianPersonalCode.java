package ee.paat.digidoc;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * Estonian personal identification code: 11 digits, a real birth date, and the
 * standard check digit (weights 1..9,1 then 3..9,1,2,3). DigiDoc4's extra
 * Mobile-ID test whitelist is not accepted.
 */
final class EstonianPersonalCode {
    private EstonianPersonalCode() {}

    static boolean isValid(String code) {
        if (code == null || code.length() != 11) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return birthDate(code) != null && checkDigit(code) == code.charAt(10) - '0';
    }

    private static LocalDate birthDate(String code) {
        int century = switch (code.charAt(0)) {
            case '1', '2' -> 1800;
            case '3', '4' -> 1900;
            case '5', '6' -> 2000;
            case '7', '8' -> 2100;
            default -> -1;
        };
        if (century < 0) return null;
        int year = century + (code.charAt(1) - '0') * 10 + (code.charAt(2) - '0');
        int month = (code.charAt(3) - '0') * 10 + (code.charAt(4) - '0');
        int day = (code.charAt(5) - '0') * 10 + (code.charAt(6) - '0');
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Same remainder rules as DigiDoc4 {@code IKValidator::isValid}. */
    private static int checkDigit(String code) {
        int sum1 = 0;
        int sum2 = 0;
        int pos1 = 1;
        int pos2 = 3;
        for (int i = 0; i < 10; i++) {
            int digit = code.charAt(i) - '0';
            sum1 += digit * pos1;
            sum2 += digit * pos2;
            pos1 = pos1 == 9 ? 1 : pos1 + 1;
            pos2 = pos2 == 9 ? 1 : pos2 + 1;
        }
        int result = sum1 % 11;
        if (result >= 10 && (result = sum2 % 11) >= 10) result = 0;
        return result;
    }
}
