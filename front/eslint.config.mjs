import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    "output/**",
    ".playwright-cli/**",
  ]),
  // 反过度工程：只收零成本规则（不需要类型信息，不会引入新依赖）。
  // 存量代码已验证零告警。no-nested-ternary 因 JSX 里三元嵌套是既有写法（35 处），不启用。
  // 需要类型信息的规则（如 @typescript-eslint/no-unnecessary-condition）见 docs/agents/autonomous-agent.md 的说明，另行评估基线后再开。
  {
    rules: {
      "no-else-return": "error",
      // 目标 3 层；存量 community-interactions.tsx:73,76 为 4 层，清理后收紧到 3。
      "max-depth": ["error", 4],
      "prefer-const": "error",
      "no-console": ["warn", { allow: ["warn", "error"] }],
    },
  },
]);

export default eslintConfig;
