/** 空态占位。图标用 emoji，避免带一套图片资源 */
Component({
  properties: {
    icon: { type: String, value: '📭' },
    text: { type: String, value: '暂无数据' },
    hint: { type: String, value: '' },
    action: { type: String, value: '' },
  },
  methods: {
    onAction(this: WxComponentThis) {
      this.triggerEvent('action');
    },
  },
});
