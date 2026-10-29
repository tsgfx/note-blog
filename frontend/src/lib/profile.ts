/**
 * 首页的个人信息。**只需要改这一个文件**，HomeView 里没有硬编码的文案。
 *
 * ⚠️ 例外：首屏右侧「现在」状态面板（aside.now 的 dl 四行）硬编码在
 * HomeView.vue 里（搜 `now-list`），改定位时别忘了那一份。
 *
 * 内容取自简历（2026-10-25 版）。定位调整：去掉具身智能/机器人方向，
 * 主打三维视觉（分割 / 重建 / 编辑）的科研方向 + 两段实习经历。
 * 想加一条链接就往 links 里追加；
 * 不想显示某一节，把数组留空即可（页面会自动隐藏空节）。
 */
export interface ProfileLink {
  /** 显示文字 */
  label: string
  /** 链接地址；留空则渲染成不可点的纯文字 */
  href?: string
  /** 悬浮时的一行说明 */
  hint?: string
}

/** 首屏下方的数字条。value + suffix 分开，是为了能做滚动计数 */
export interface Highlight {
  value: number
  suffix: string
  /** 小数位，0 表示整数 */
  decimals: number
  label: string
  /** 数字的出处，写得越具体越好，避免看起来像吹的 */
  source: string
}

/** 「在做的事」三张卡 */
export interface FocusItem {
  index: string
  title: string
  body: string
  tags: string[]
}

/** 经历时间线 */
export interface Track {
  period: string
  role: string
  org: string
  place: string
  points: string[]
  tags: string[]
}

export interface Profile {
  /** 中文名，首屏大字 */
  name: string
  /** 英文名 */
  latin: string
  /** 常用 ID */
  handle: string
  /** 一行定位，做字距展开入场 */
  role: string
  /** 首屏状态行，配一个呼吸圆点 */
  status: string
  /** 自我介绍，2–3 句为宜 */
  intro: string
  location: string
  /** 头像图片地址，留空时用名字首字 */
  avatar?: string
  links: ProfileLink[]
  highlights: Highlight[]
  focus: FocusItem[]
  tracks: Track[]
  /** 工具箱，会做一条缓慢横向滚动的标签带 */
  tools: string[]
  /** 页脚一句话 */
  footer: string
}

export const profile: Profile = {
  name: '郭瑞超',
  latin: 'Ruichao Guo',
  handle: 'tsgfx',
  role: '三维视觉 · 大模型工程',
  status: '计算机技术硕士在读',
  intro:
    '在太原理工大学读计算机技术，研究方向是三维视觉：点云与场景的分割、重建、编辑。实习做过大模型工程化落地（ASR 纠错、评测体系、RAG 客服）和 C++ 高并发后端。这个站点是我自己写的笔记系统，文章会先长在这里。',
  location: '太原，中国',
  avatar: '',
  links: [
    { label: 'GitHub', href: 'https://github.com/tsgfx', hint: '代码与开源' },
    { label: '语雀', href: 'https://www.yuque.com/tsgfx', hint: '长期维护的技术笔记' },
    { label: '邮箱', href: 'mailto:grcsxb269@163.com', hint: '随时可以聊' },
    { label: 'RSS', href: '/api/blog/rss.xml', hint: '订阅更新' },
  ],
  highlights: [
    {
      value: 3,
      suffix: ' 段',
      decimals: 0,
      label: '实习与项目经历',
      source: '牛客 NLP 实习 · GLM 客服独立项目 · 华普众成 C++ 实习',
    },
    {
      value: 97.7,
      suffix: '%',
      decimals: 1,
      label: 'ASR 纠错准确率',
      source: '牛客 · DeepSeek-V3，同任务 GPT-4o 为 59.1%',
    },
    {
      value: 78.4,
      suffix: '%',
      decimals: 1,
      label: '点云语义分割 mIoU',
      source: '本科毕设 · S3DIS 子集，基准约 72%',
    },
    {
      value: 680,
      suffix: '+',
      decimals: 0,
      label: '篇技术笔记沉淀',
      source: '自研笔记系统 · Spring Boot + Vue 3，向量检索问答',
    },
  ],
  focus: [
    {
      index: '01',
      title: '三维分割',
      body: '室内点云语义分割：点-体素混合网络（Voxel + PointNet++）配合自定义边界平滑损失，S3DIS 子集 mIoU 78.4%；小样本场景正在尝试元学习特征编码与几何自注意力（ScanNet 调优中）。',
      tags: ['PointNet++', 'S3DIS', 'ScanNet'],
    },
    {
      index: '02',
      title: '三维重建',
      body: '面向室内场景的三维重建：从 RGB-D 与多视角输入恢复几何结构，为上层的分割与编辑提供几何底座。目前在方案调研与小规模验证阶段。',
      tags: ['RGB-D', '多视角几何'],
    },
    {
      index: '03',
      title: '二维分割 · 三维编辑',
      body: '二维图像分割作为三维任务的前置能力在研；三维编辑探索场景级几何与语义的受控修改，与重建、分割串成一条链路。',
      tags: ['图像分割', '3D 编辑'],
    },
  ],
  tracks: [
    {
      period: '2025.09 — 至今',
      role: '计算机技术 · 硕士研究生',
      org: '太原理工大学',
      place: '太原',
      points: [
        '研究方向：三维分割 / 三维重建 / 三维编辑 / 二维分割',
        '在研：小样本点云语义分割，元学习特征编码 + 几何自注意力，ScanNet 上实验调优',
      ],
      tags: ['在读'],
    },
    {
      period: '2025.05 — 2025.08',
      role: 'NLP 算法实习生',
      org: '北京牛客科技',
      place: '上海',
      points: [
        'ASR 智能纠错：设计结构化 Prompt 与量化评估体系，DeepSeek-V3 准确率 97.7%，时延 −42%，成本降至 1/15',
        'AI 面试平台与 UltraFlow 重构：Jinja2 优化模板，多线程 FlowProcessor 让批测效率提升 10 倍',
      ],
      tags: ['LLM', 'Prompt 工程'],
    },
    {
      period: '2024.10 — 2025.02',
      role: '大模型应用 · 独立项目',
      org: 'GLM 智能客服机器人',
      place: '',
      points: [
        'P-Tuning v2 微调 GLM-4-9B，LangChain 知识库检索 + 重排序缓解幻觉，BLEU-4 38.2',
        'Spring Boot + RabbitMQ 异步削峰、Redis 多轮上下文，QPS 50+；Vue3 流式打字机交互',
      ],
      tags: ['LLM 微调', 'RAG', '全栈'],
    },
    {
      period: '2023.06 — 2023.09',
      role: 'C++ 后端开发实习生',
      org: '山西华普众成软件',
      place: '太原',
      points: [
        '政府气象数据管理平台：多线程数据访问接口，优化至每秒 3–5 千次访问',
        'XML 动态解析与入库组件，数据处理吞吐率提升约 40%',
      ],
      tags: ['C++', 'Oracle'],
    },
    {
      period: '2021.09 — 2025.06',
      role: '计算机科学与技术 · 工学学士',
      org: '河南大学',
      place: '郑州',
      points: [
        '均分 89.24 / GPA 3.56（前 15%），Victoria University 双学位联合培养',
        '优秀毕业生 · 校奖学金 · 单项奖学金 · 优秀实习生',
      ],
      tags: ['学士'],
    },
  ],
  tools: [
    'Python',
    'PyTorch',
    'C++',
    'Java',
    'Spring Boot',
    'Vue 3',
    'Docker',
    'MySQL',
    'RAG',
    'PointNet++',
    'OpenCV',
    'PCL',
    'Linux',
    'Git',
  ],
  footer: '自己写的笔记系统：Spring Boot + Vue 3，检索走 bge-m3 向量 + 关键词融合。',
}
