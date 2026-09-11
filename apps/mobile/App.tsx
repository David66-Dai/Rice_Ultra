import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View } from 'react-native';

export default function App() {
  return (
    <View style={styles.container}>
      <Text style={styles.brand}>数智稻安</Text>
      <Text style={styles.subtitle}>水稻农田智能监测预警 · 独立 App</Text>
      <Text style={styles.hint}>环境已就绪，可在此开始业务页面开发</Text>
      <StatusBar style="light" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f3d2e',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  brand: {
    fontSize: 36,
    fontWeight: '700',
    color: '#e8f5e9',
    letterSpacing: 2,
  },
  subtitle: {
    marginTop: 12,
    fontSize: 15,
    color: '#a5d6a7',
    textAlign: 'center',
  },
  hint: {
    marginTop: 28,
    fontSize: 13,
    color: '#81c784',
    textAlign: 'center',
  },
});
