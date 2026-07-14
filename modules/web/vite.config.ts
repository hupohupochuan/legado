import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { viteSingleFile } from "vite-plugin-singlefile";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  return {
    plugins: [
      vue(),
      AutoImport({
        imports: ["vue", "vue-router", "pinia"],
        include: [/\.[tj]sx?$/, /\.vue$/, /\.vue\?vue/],
        dirs: ["src/components", "src/store", "*.d.ts"],
        dts: "./src/auto-imports.d.ts",
      }),
      Components({
        dts: "./src/components.d.ts",
      }),
      viteSingleFile(),
    ],
    base: mode === "development" ? "/" : "./",
    server: {
      port: 8080,
    },
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
        "@api": fileURLToPath(new URL("./src/api", import.meta.url)),
        "@utils": fileURLToPath(new URL("./src/utils/", import.meta.url)),
      },
    },
    build: {
      target: ["es2020", "edge88", "firefox78", "chrome87", "safari14"],
      reportCompressedSize: true,
      emptyOutDir: true,
      rolldownOptions: {
        output: {
          minify: {
            compress: {
              dropConsole: mode !== "development",
              dropDebugger: mode !== "development",
            },
          },
        },
      },
    },
  }
});
