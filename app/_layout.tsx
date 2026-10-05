import {
  Inter_400Regular,
  Inter_500Medium,
  Inter_600SemiBold,
  Inter_700Bold,
  useFonts,
} from "@expo-google-fonts/inter";
import { QueryClientProvider } from "@tanstack/react-query";
import { Stack } from "expo-router";
import * as SplashScreen from "expo-splash-screen";
import React, { useEffect } from "react";
import { StatusBar } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { KeyboardProvider } from "react-native-keyboard-controller";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import TouchInterceptor from "@/components/TouchInterceptor";
import { queryClient } from "@/lib/query-client";
import { UltraDevLog } from '@/src/utils/UltraDevLog';
import { AgentCoreProvider } from '@/src/context/AgentCoreContext';
import '@/src/core/HeadlessReActHandler'; // registers HeadlessJS task for background ReActLoop

SplashScreen.preventAutoHideAsync();

function RootLayoutNav() {
  return (
    <Stack
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: "#000000" },
        animation: "slide_from_right",
      }}
    >
      <Stack.Screen name="index" />
      <Stack.Screen
        name="settings"
        options={{
          presentation: "modal",
          animation: "slide_from_bottom",
        }}
      />
    </Stack>
  );
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
    Inter_700Bold,
  });

  useEffect(() => {
    if (fontsLoaded || fontError) {
      SplashScreen.hideAsync();
    }
  }, [fontsLoaded, fontError]);

  useEffect(() => {
    UltraDevLog.collectEnvelopeContext();
  }, []);

  // Global JS error capture — catches everything React ErrorBoundary misses
  useEffect(() => {
    // Unhandled promise rejections (the #1 source of silent failures)
    const rejectionHandler = (event: any) => {
      const error = event?.reason;
      const message = error?.message || error?.toString?.() || 'Unknown rejection';
      const stack = error?.stack || '';
      UltraDevLog.error('UNHANDLED_REJECTION', message, stack);
      console.error('[Ultra] Unhandled rejection:', message);
    };

    // Uncaught synchronous errors (rare in React Native but possible)
    const errorHandler = (event: any) => {
      const error = event?.error || event;
      const message = error?.message || error?.toString?.() || 'Unknown error';
      const stack = error?.stack || '';
      UltraDevLog.error('UNCAUGHT_ERROR', message, stack);
      console.error('[Ultra] Uncaught error:', message);
    };

    // React Native's global error handler
    const prevHandler = (global as any).ErrorUtils?.getGlobalHandler?.();
    (global as any).ErrorUtils?.setGlobalHandler?.((error: Error, isFatal?: boolean) => {
      try {
        UltraDevLog.error(
          isFatal ? 'FATAL_JS_ERROR' : 'JS_ERROR',
          error?.message || 'Unknown',
          error?.stack || ''
        );
        UltraDevLog.flushToFile();
        if (isFatal) {
          import('../src/services/DebugScreenshots').then(m => m.DebugScreenshots.capture('fatal_error')).catch(() => {});
        }
      } catch { /* don't recurse */ }
      if (prevHandler) prevHandler(error, isFatal);
    });

    // Web-style handlers (some RN environments support these)
    if (typeof global !== 'undefined') {
      (global as any).onunhandledrejection = rejectionHandler;
    }

    return () => {
      if (prevHandler) {
        (global as any).ErrorUtils?.setGlobalHandler?.(prevHandler);
      }
    };
  }, []);

  if (!fontsLoaded && !fontError) return null;

  return (
    <SafeAreaProvider>
      <TouchInterceptor>
      <ErrorBoundary>
        <QueryClientProvider client={queryClient}>
          <GestureHandlerRootView style={{ flex: 1 }}>
            <KeyboardProvider>
              <StatusBar barStyle="light-content" backgroundColor="#000000" />
              <AgentCoreProvider>
                <RootLayoutNav />
              </AgentCoreProvider>
            </KeyboardProvider>
          </GestureHandlerRootView>
        </QueryClientProvider>
      </ErrorBoundary>
      </TouchInterceptor>
    </SafeAreaProvider>
  );
}
