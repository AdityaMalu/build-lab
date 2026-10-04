package returns;

import java.util.List;

public record RiskResult(int score, RiskLevel level, List<String> reasons) {
}
