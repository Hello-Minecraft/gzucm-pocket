/**
 * 最小化的微信小程序类型声明。
 *
 * 正式项目应该装 `miniprogram-api-typings`，但这台机器不方便跑 npm，
 * 所以这里手写一份够用的。只声明本项目实际用到的 API，
 * 参数用宽松类型 —— 目的是让 TS 能编译，不是做完整类型检查。
 */

interface WxFail {
  errMsg?: string;
  errno?: number;
}

interface WxRequestRes<T = any> {
  data: T;
  statusCode: number;
  header: Record<string, string>;
  cookies?: string[];
}

interface WxRequestOption {
  url: string;
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'HEAD' | 'OPTIONS' | 'TRACE' | 'CONNECT';
  data?: any;
  header?: Record<string, string>;
  timeout?: number;
  dataType?: string;
  responseType?: 'text' | 'arraybuffer';
  success?: (res: WxRequestRes) => void;
  fail?: (err: WxFail) => void;
  complete?: () => void;
}

interface WxCloudCallOption {
  name: string;
  data?: any;
  success?: (res: { result: any }) => void;
  fail?: (err: WxFail) => void;
}

interface WxStorageInfo {
  keys: string[];
  currentSize: number;
  limitSize: number;
}

interface Wx {
  request(opt: WxRequestOption): void;

  cloud: {
    init(opt?: { env?: string; traceUser?: boolean }): void;
    callFunction(opt: WxCloudCallOption): void;
  };

  getStorageSync(key: string): any;
  setStorageSync(key: string, value: any): void;
  removeStorageSync(key: string): void;
  getStorageInfoSync(): WxStorageInfo;
  clearStorageSync(): void;

  showToast(opt: { title: string; icon?: string; duration?: number }): void;
  showLoading(opt: { title: string; mask?: boolean }): void;
  hideLoading(): void;
  showModal(opt: {
    title?: string;
    content?: string;
    showCancel?: boolean;
    confirmText?: string;
    cancelText?: string;
    success?: (res: { confirm: boolean; cancel: boolean }) => void;
  }): void;
  showActionSheet(opt: {
    itemList: string[];
    success?: (res: { tapIndex: number }) => void;
    fail?: () => void;
  }): void;

  navigateTo(opt: { url: string; success?: () => void; fail?: () => void }): void;
  redirectTo(opt: { url: string }): void;
  reLaunch(opt: { url: string }): void;
  switchTab(opt: { url: string }): void;
  navigateBack(opt?: { delta?: number }): void;

  setNavigationBarTitle(opt: { title: string }): void;
  setNavigationBarColor(opt: {
    frontColor: string;
    backgroundColor: string;
    animation?: { duration?: number };
  }): void;

  getSystemInfoSync(): {
    statusBarHeight: number;
    windowWidth: number;
    windowHeight: number;
    windowTop: number;
    safeArea?: { top: number; bottom: number; height: number };
    platform: string;
    pixelRatio: number;
  };

  getMenuButtonBoundingClientRect(): {
    width: number;
    height: number;
    top: number;
    bottom: number;
    left: number;
    right: number;
  };

  createSelectorQuery(): {
    select(sel: string): {
      boundingClientRect(cb: (rect: any) => void): { exec(cb?: () => void): void };
      fields(opt: any, cb: (res: any) => void): { exec(cb?: () => void): void };
    };
    selectAll(sel: string): { boundingClientRect(cb: (res: any) => void): { exec(cb?: () => void): void } };
    in(component: any): any;
    exec(cb?: (res: any) => void): void;
  };

  setClipboardData(opt: { data: string; success?: () => void }): void;

  /** 下载图片（课表背景） */
  downloadFile(opt: {
    url: string;
    success?: (res: { tempFilePath: string; statusCode: number }) => void;
    fail?: (err: WxFail) => void;
  }): void;

  getFileSystemManager(): {
    saveFile(opt: {
      tempFilePath: string;
      filePath?: string;
      success?: (res: { savedFilePath: string }) => void;
      fail?: (err: WxFail) => void;
    }): void;
    access(opt: { path: string; success?: () => void; fail?: () => void }): void;
    unlink(opt: { filePath: string; success?: () => void; fail?: () => void }): void;
  };

  chooseMedia(opt: {
    count?: number;
    mediaType?: string[];
    success?: (res: { tempFiles: Array<{ tempFilePath: string; size: number }> }) => void;
    fail?: (err: WxFail) => void;
  }): void;

  onKeyboardHeightChange(cb: (res: any) => void): void;
  offKeyboardHeightChange(cb?: (res: any) => void): void;
}

declare const wx: Wx;

/**
 * 页面 / 组件 / App 的声明。
 *
 * 这里返回「任意类型」而不是具体接口，原因是：
 * 小程序的 Page/Component 里 this 上挂的是自定义字段（如 this.timer、this.qrcodeId），
 * 声明成具体接口反而会把它们全判成错误。代价是丢了 this 的类型检查，
 * 换来的是不必为每个页面写一份 ThisType —— 对小项目这笔账划算。
 */
declare function App(options: any): void;
declare function Page(options: any): void;

/**
 * 组件的 this 需要显式类型，否则 TS 会把 observers / methods 推断成
 * 一个只有几个方法的字面量对象，this.setData / triggerEvent 全部报错。
 * 这里给一个「什么都能挂」的接口，够用且不啰嗦。
 */
interface WxComponentThis {
  data: any;
  properties: any;
  setData(patch: Record<string, any>, cb?: () => void): void;
  triggerEvent(name: string, detail?: any, options?: any): void;
  selectComponent(sel: string): any;
  [key: string]: any;
}
declare function Component(options: any & ThisType<WxComponentThis>): void;

declare function getApp(): any;
declare function getCurrentPages(): any[];

/** 定时器。tsconfig 的 lib 只到 ES2020，不含 DOM，所以要自己声明 */
declare function setTimeout(
  handler: (...args: any[]) => void,
  timeout?: number,
  ...args: any[]
): number;
declare function clearTimeout(id?: number): void;
declare function setInterval(
  handler: (...args: any[]) => void,
  timeout?: number,
  ...args: any[]
): number;
declare function clearInterval(id?: number): void;

/** 组件触发事件 */
interface WxEvent {
  type: string;
  detail: any;
  currentTarget: { dataset: Record<string, any>; id: string };
  target: { dataset: Record<string, any>; id: string };
}