# XiaoyuMotd-Velocity 🎨

一个专为 **Velocity 代理服务器** 打造的高性能自定义 MOTD 插件。该插件利用 Minecraft 1.21.9+ 引入的新特性，通过玩家头颅（Player Heads）拼接技术，在服务器列表中实现精美的图片展示。

> [!IMPORTANT]
> **版本限制**: 本插件利用了 Minecraft 1.21.9 引入的新特性（ObjectComponent），因此**不支持**任何低于 1.21.9 的客户端版本查看图片效果。

---

## ✨ 核心特性

- **🖼️ 图片转头颅像素画**: 自动将本地图片切割为 8x8 像素块，并将其转换为 64x64 标准皮肤格式。
- **☁️ MineSkin 自动集成**: 深度集成 [MineSkin API](https://mineskin.org/)，一键上传并获取贴图 URL，无需手动操作。
- **⚡ 高性能缓存系统**: 
    - 自动缓存已处理的贴图 URL。
    - 支持数据混淆存储，保护贴图隐私。
    - 极大减少重复上传带来的延迟。
- **🚀 现代技术栈**: 
    - 基于 **Java 21** 开发。
    - 专为 **Velocity** 架构优化，支持异步处理图片及上传任务。
    - 使用 **Adventure API** 构建现代文本组件。

---

## 🛠️ 安装与配置

1. 下载 `XiaoyuMotd-Velocity.jar` 并放入 Velocity 的 `plugins` 文件夹。
2. 启动服务器以生成默认配置。
3. 编辑 `plugins/xiaoyumotd/config.yml`:

```yaml
image-motd:
  # 使用的图片缓存文件名（存储在 plugins/xiaoyumotd/cache/ 目录下）
  image: MOTD.cache

# MineSkin API Key (强烈建议配置)
# 请前往 https://mineskin.org/ 注册并获取密钥，以解除上传频率限制
api-key: "你的_API_KEY"
```

---

## 🎮 命令与权限

| 命令 | 说明 | 权限节点 |
| :--- | :--- | :--- |
| `/xiaoyumotd reload` | 重新加载插件配置文件及 MOTD 缓存 | `xiaoyumotd.admin` |
| `/xiaoyumotd upload <文件名>` | 处理并上传指定的图片（需放在 images 目录） | `xiaoyumotd.admin` |

> **提示**: 待处理的图片需预先放入 `/plugins/xiaoyumotd/images/` 文件夹。

---

## 🚀 快速上手

1. **准备图片**: 准备一张适合拼接的像素图（建议长宽为 8 的倍数），放入 `plugins/xiaoyumotd/images/`。
2. **执行上传**: 在游戏内或控制台输入 `/xiaoyumotd upload myimage.png`。
3. **等待完成**: 插件会自动进行切割、上传并保存至 `cache` 目录。
4. **生效配置**: 在 `config.yml` 中将 `image` 项改为 `myimage.cache` 并执行 `/xiaoyumotd reload`。

---

## 🧠 实现原理

本插件通过以下技术流程实现“全图片 MOTD”：

1. **图片切片**: 插件会将您提供的图片按 **8x8 像素** 进行网格化切割。
2. **皮肤生成**: 每一个 8x8 的像素块会被填充到一个标准的 **64x64 像素** 皮肤贴图中。
3. **云端上传**: 利用 [MineSkin](https://mineskin.org/) 服务将生成的皮肤贴图上传到 Mojang 服务器，获取对应的 **Texture URL**。
4. **组件拼接**:
    - 利用 Minecraft **1.21.9+** 引入的 **ObjectComponent** 功能。
    - 通过 **Adventure API** 构建包含这些头颅的 JSON 文本组件。
    - 按照原始图片的行列顺序，将这些“头颅像素”精确排列。
5. **高性能缓存**: 为了避免重复上传和请求延迟，插件会将处理好的贴图数据混淆后缓存至本地。

---

## 📜 参考与致谢

该插件的实现思路及技术方案参考了 [PuddingKC 的教程](https://www.puddingkc.com/pages/f7a588/)。感谢其在 Minecraft MOTD 创新展示方面的探索与分享。

---

## ⚠️ 注意事项

- 图片大小直接影响 MOTD 的加载速度和协议包大小（建议单行头颅不超过 33 个）。
- 由于 Velocity 处理 Ping 事件的特殊性，建议使用较小的像素图以保证连接稳定性。
