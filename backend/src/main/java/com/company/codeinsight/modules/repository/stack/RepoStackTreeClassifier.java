package com.company.codeinsight.modules.repository.stack;

import com.company.codeinsight.modules.repository.model.RepoType;
import com.company.codeinsight.modules.repository.model.TechStackCatalog;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 根据相对路径列表（文件名级）识别前端/后端技术栈。
 */
public final class RepoStackTreeClassifier {

    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    @Getter
    public static final class Result {
        private final String repoType;
        private final String techStack;
        private final Confidence confidence;
        private final List<String> evidence;

        public Result(String repoType, String techStack, Confidence confidence, List<String> evidence) {
            this.repoType = repoType;
            this.techStack = techStack;
            this.confidence = confidence;
            this.evidence = List.copyOf(evidence);
        }

        public boolean isHighEnough() {
            return confidence == Confidence.HIGH || confidence == Confidence.MEDIUM;
        }
    }

    private RepoStackTreeClassifier() {
    }

    public static Result classify(List<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return null;
        }

        Map<String, Integer> stackScore = new HashMap<>();
        Map<String, Integer> typeScore = new HashMap<>();
        List<String> evidence = new ArrayList<>();

        boolean hasPackageJson = false;
        boolean hasReact = false;
        boolean hasVue = false;
        boolean hasAngular = false;
        boolean hasNext = false;
        boolean hasFlutter = false;
        boolean hasPom = false;
        boolean hasGradle = false;
        boolean hasGoMod = false;
        boolean hasCsproj = false;
        boolean hasPyProject = false;
        boolean hasRequirements = false;
        int javaFiles = 0;
        int pyFiles = 0;
        int goFiles = 0;
        int csFiles = 0;
        int vueFiles = 0;
        int tsxFiles = 0;

        for (String raw : relativePaths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String path = raw.replace('\\', '/');
            String lower = path.toLowerCase(Locale.ROOT);
            String base = baseName(lower);

            if (base.equals("pom.xml")) {
                hasPom = true;
                evidence.add("pom.xml");
            } else if (base.equals("build.gradle") || base.equals("build.gradle.kts")) {
                hasGradle = true;
                evidence.add(base);
            } else if (base.equals("go.mod")) {
                hasGoMod = true;
                evidence.add("go.mod");
            } else if (base.endsWith(".csproj") || base.endsWith(".sln")) {
                hasCsproj = true;
                evidence.add(base);
            } else if (base.equals("pyproject.toml")) {
                hasPyProject = true;
                evidence.add("pyproject.toml");
            } else if (base.equals("requirements.txt")) {
                hasRequirements = true;
                evidence.add("requirements.txt");
            } else if (base.equals("package.json")) {
                hasPackageJson = true;
                evidence.add("package.json");
            } else if (base.equals("pubspec.yaml") || base.equals("pubspec.yml")) {
                hasFlutter = true;
                evidence.add(base);
            } else if (base.equals("angular.json")) {
                hasAngular = true;
                evidence.add("angular.json");
            } else if (base.startsWith("next.config.")) {
                hasNext = true;
                evidence.add(base);
            } else if (base.endsWith(".java")) {
                javaFiles++;
            } else if (base.endsWith(".py")) {
                pyFiles++;
            } else if (base.endsWith(".go")) {
                goFiles++;
            } else if (base.endsWith(".cs")) {
                csFiles++;
            } else if (base.endsWith(".vue")) {
                vueFiles++;
            } else if (base.endsWith(".tsx") || base.endsWith(".jsx")) {
                tsxFiles++;
            }

            if (lower.contains("/react") || lower.contains("react-") || base.contains("react")) {
                hasReact = true;
            }
            if (lower.contains("/vue") || base.endsWith(".vue")) {
                hasVue = true;
            }
            if (lower.contains("@angular") || lower.contains("/angular")) {
                hasAngular = true;
            }
            if (lower.contains("flutter")) {
                hasFlutter = true;
            }
        }

        if (hasPom || hasGradle || javaFiles >= 3) {
            bump(stackScore, "Java", 100 + Math.min(javaFiles, 50));
            bump(typeScore, RepoType.BACKEND.getCode(), 100);
            if (hasPom || hasGradle) {
                evidence.add("java-build");
            }
        }
        if (hasPyProject || hasRequirements || pyFiles >= 3) {
            bump(stackScore, "Python", 90 + Math.min(pyFiles, 40));
            bump(typeScore, RepoType.BACKEND.getCode(), 90);
        }
        if (hasGoMod || goFiles >= 3) {
            bump(stackScore, "Go", 95 + Math.min(goFiles, 40));
            bump(typeScore, RepoType.BACKEND.getCode(), 95);
        }
        if (hasCsproj || csFiles >= 3) {
            bump(stackScore, "C#", 95 + Math.min(csFiles, 40));
            bump(typeScore, RepoType.BACKEND.getCode(), 95);
        }

        if (hasFlutter) {
            bump(stackScore, "Flutter", 110);
            bump(typeScore, RepoType.FRONTEND.getCode(), 110);
        }
        if (hasNext) {
            bump(stackScore, "Next.js", 105);
            bump(typeScore, RepoType.FRONTEND.getCode(), 105);
        }
        if (hasAngular || (hasPackageJson && hasAngular)) {
            bump(stackScore, "Angular", 100);
            bump(typeScore, RepoType.FRONTEND.getCode(), 100);
        }
        if (hasVue || vueFiles >= 2) {
            bump(stackScore, "Vue", 100 + Math.min(vueFiles, 30));
            bump(typeScore, RepoType.FRONTEND.getCode(), 100);
        }
        if (hasReact || tsxFiles >= 3) {
            bump(stackScore, "React", 100 + Math.min(tsxFiles, 30));
            bump(typeScore, RepoType.FRONTEND.getCode(), 100);
        }

        // package.json 且无强前端证据 → Node.js 后端弱信号
        if (hasPackageJson && typeScore.getOrDefault(RepoType.FRONTEND.getCode(), 0) == 0
                && !hasPom && !hasGradle && javaFiles == 0) {
            bump(stackScore, "Node.js", 60);
            bump(typeScore, RepoType.BACKEND.getCode(), 60);
            evidence.add("package.json→Node.js");
        }

        String bestType = topKey(typeScore);
        String bestStack = topKey(stackScore);

        int fe = typeScore.getOrDefault(RepoType.FRONTEND.getCode(), 0);
        int be = typeScore.getOrDefault(RepoType.BACKEND.getCode(), 0);
        if (fe > 0 && be > 0 && Math.abs(fe - be) < 30) {
            // monorepo / 冲突：仅此分支标「前后端」+ 双侧 top 栈（逗号）
            String beStack = topStackAmong(stackScore, RepoType.BACKEND.getCode());
            String feStack = topStackAmong(stackScore, RepoType.FRONTEND.getCode());
            if (beStack != null && !beStack.isBlank() && feStack != null && !feStack.isBlank()) {
                String joined = TechStackCatalog.joinStacks(beStack, feStack);
                evidence.add("fullstack-conflict fe=" + fe + " be=" + be);
                return new Result(RepoType.FULLSTACK.getCode(), joined, Confidence.HIGH, evidence);
            }
            return new Result(null, null, Confidence.LOW, evidence);
        }

        if (bestType == null || bestStack == null) {
            return null;
        }

        if (!TechStackCatalog.isValidPair(bestType, bestStack)) {
            // 栈与类型不一致时，按类型目录内最高分栈重选
            String realigned = TechStackCatalog.stacksOf(bestType).stream()
                    .max(Comparator.comparingInt(s -> stackScore.getOrDefault(s, 0)))
                    .orElse(null);
            if (realigned == null || stackScore.getOrDefault(realigned, 0) <= 0
                    || !TechStackCatalog.isValidPair(bestType, realigned)) {
                return null;
            }
            bestStack = realigned;
        }

        final String chosenStack = bestStack;
        int top = stackScore.getOrDefault(chosenStack, 0);
        int second = stackScore.entrySet().stream()
                .filter(e -> !e.getKey().equals(chosenStack))
                .mapToInt(Map.Entry::getValue)
                .max()
                .orElse(0);
        Confidence confidence;
        if (top >= 90 && top - second >= 20) {
            confidence = Confidence.HIGH;
        } else if (top >= 60) {
            confidence = Confidence.MEDIUM;
        } else {
            confidence = Confidence.LOW;
        }

        if (confidence == Confidence.LOW) {
            return new Result(bestType, chosenStack, confidence, evidence);
        }
        return new Result(bestType, chosenStack, confidence, evidence);
    }

    private static String topStackAmong(Map<String, Integer> stackScore, String repoTypeCode) {
        return TechStackCatalog.stacksOf(repoTypeCode).stream()
                .filter(s -> stackScore.getOrDefault(s, 0) > 0)
                .max(Comparator.comparingInt(s -> stackScore.getOrDefault(s, 0)))
                .orElse(null);
    }

    private static void bump(Map<String, Integer> map, String key, int delta) {
        map.merge(key, delta, Integer::sum);
    }

    private static String topKey(Map<String, Integer> map) {
        return map.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
