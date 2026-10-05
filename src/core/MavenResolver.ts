import * as ExpoFileSystem from 'expo-file-system/legacy';
import { Platform } from 'react-native';
import { UltraDevLog } from '../utils/UltraDevLog';

const FS: any = Platform.OS !== 'web' ? ExpoFileSystem : null;
const MAVEN_CENTRAL = 'https://repo1.maven.org/maven2';

function getCacheDir(): string {
  const docDir = (FS?.documentDirectory) || '';
  return `${docDir}maven_cache`;
}

export class MavenResolver {
  private cacheDir: string;

  constructor() {
    this.cacheDir = getCacheDir();
    UltraDevLog.push('SYSTEM', { event: 'maven_resolver_init', cacheDir: this.cacheDir, platform: Platform.OS });
  }

  async resolveAll(coordinates: string[]): Promise<string[]> {
    UltraDevLog.push('SYSTEM', { event: 'maven_resolve_all_start', count: coordinates.length });
    await this.ensureCacheDir();
    const paths: string[] = [];
    for (const coord of coordinates) {
      const path = await this.resolve(coord);
      if (path) paths.push(path);
    }
    UltraDevLog.push('SYSTEM', { event: 'maven_resolve_all_done', requested: coordinates.length, resolved: paths.length });
    return paths;
  }

  async resolve(coordinate: string): Promise<string | null> {
    const parts = coordinate.split(':');
    if (parts.length !== 3) {
      UltraDevLog.push('SYSTEM', { event: 'maven_resolve_invalid_coord', coordinate });
      return null;
    }
    const [group, artifact, version] = parts;
    const groupPath = group.replace(/\./g, '/');
    const jarName = `${artifact}-${version}.jar`;
    const localPath = `${this.cacheDir}/${group}/${artifact}/${version}/${jarName}`;

    const info = await FS.getInfoAsync(localPath);
    if (info.exists && (info as any).size > 0) {
      UltraDevLog.push('SYSTEM', { event: 'maven_resolve_cache_hit', coordinate, localPath });
      return localPath;
    }

    const url = `${MAVEN_CENTRAL}/${groupPath}/${artifact}/${version}/${jarName}`;
    UltraDevLog.push('SYSTEM', { event: 'maven_resolve_download_start', coordinate, url });

    try {
      const parentDir = localPath.substring(0, localPath.lastIndexOf('/'));
      await FS.makeDirectoryAsync(parentDir, { intermediates: true });

      const download = await FS.downloadAsync(url, localPath);
      if (download.status !== 200) {
        UltraDevLog.push('SYSTEM', { event: 'maven_resolve_download_fail', coordinate, url, httpStatus: download.status, fallback: 'aar' });
        return this.resolveAar(group, artifact, version);
      }
      UltraDevLog.push('SYSTEM', { event: 'maven_resolve_download_ok', coordinate, localPath });
      return localPath;
    } catch (e: any) {
      UltraDevLog.push('SYSTEM', { event: 'maven_resolve_download_error', coordinate, url, error: e?.message });
      return null;
    }
  }

  private async resolveAar(group: string, artifact: string, version: string): Promise<string | null> {
    const groupPath = group.replace(/\./g, '/');
    const aarName = `${artifact}-${version}.aar`;
    const localPath = `${this.cacheDir}/${group}/${artifact}/${version}/${aarName}`;

    const info = await FS.getInfoAsync(localPath);
    if (info.exists && (info as any).size > 0) {
      UltraDevLog.push('SYSTEM', { event: 'maven_aar_cache_hit', coord: `${group}:${artifact}:${version}`, localPath });
      return localPath;
    }

    const url = `${MAVEN_CENTRAL}/${groupPath}/${artifact}/${version}/${aarName}`;
    UltraDevLog.push('SYSTEM', { event: 'maven_aar_download_start', coord: `${group}:${artifact}:${version}`, url });
    try {
      const download = await FS.downloadAsync(url, localPath);
      if (download.status !== 200) {
        UltraDevLog.push('SYSTEM', { event: 'maven_aar_download_fail', coord: `${group}:${artifact}:${version}`, httpStatus: download.status });
        return null;
      }
      UltraDevLog.push('SYSTEM', { event: 'maven_aar_download_ok', coord: `${group}:${artifact}:${version}`, localPath });
      return localPath;
    } catch (e: any) {
      UltraDevLog.push('SYSTEM', { event: 'maven_aar_download_error', coord: `${group}:${artifact}:${version}`, error: e?.message });
      return null;
    }
  }

  async listCached(): Promise<string[]> {
    if (!FS) return [];
    try {
      const info = await FS.getInfoAsync(this.cacheDir);
      if (!info.exists) return [];
      return this.listFiles(this.cacheDir);
    } catch {
      return [];
    }
  }

  async clearCache(): Promise<void> {
    if (!FS) return;
    try {
      const info = await FS.getInfoAsync(this.cacheDir);
      if (info.exists) {
        await FS.deleteAsync(this.cacheDir, { idempotent: true });
        UltraDevLog.push('SYSTEM', { event: 'maven_cache_cleared', cacheDir: this.cacheDir });
      }
    } catch (e: any) {
      UltraDevLog.push('SYSTEM', { event: 'maven_cache_clear_fail', error: e?.message });
    }
  }

  private async ensureCacheDir(): Promise<void> {
    if (!FS) return;
    const info = await FS.getInfoAsync(this.cacheDir);
    if (!info.exists) {
      await FS.makeDirectoryAsync(this.cacheDir, { intermediates: true });
    }
  }

  private async listFiles(dir: string): Promise<string[]> {
    try {
      const entries = await FS.readDirectoryAsync(dir);
      const result: string[] = [];
      for (const entry of entries) {
        const fullPath = `${dir}/${entry}`;
        const info = await FS.getInfoAsync(fullPath);
        if (info.isDirectory) {
          result.push(...(await this.listFiles(fullPath)));
        } else {
          result.push(fullPath);
        }
      }
      return result;
    } catch {
      return [];
    }
  }
}
