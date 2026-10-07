// @ts-check
import eslint from "@eslint/js";
import solid from "eslint-plugin-solid";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist/**", "coverage/**", "node_modules/**", "*.config.ts", "*.config.js", "*.cjs"] },
  eslint.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,
  solid.configs["flat/recommended"],
  {
    files: ["src/**/*.{ts,tsx}", "test/**/*.{ts,tsx}"],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      "@typescript-eslint/no-floating-promises": "error",
      "@typescript-eslint/switch-exhaustiveness-check": ["error", { considerDefaultExhaustiveForUnions: true }],
      "@typescript-eslint/consistent-type-imports": "error",
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-non-null-assertion": "error",
      "@typescript-eslint/no-unnecessary-condition": "error",
      "@typescript-eslint/strict-boolean-expressions": "error",
      "no-shadow": "off",
      "@typescript-eslint/no-shadow": "error",
      "no-console": "error",
      eqeqeq: ["error", "always"],
    },
  },
  {
    files: ["*.config.{js,ts}"],
    languageOptions: {
      globals: globals.node,
    },
  },
);
