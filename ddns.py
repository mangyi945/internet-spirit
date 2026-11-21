#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把当前的公网地址「定格」成一个固定域名（DDNS）。

为什么需要这个：
    运营商给的地址会变，朋友的暗号里写死 IP 就失效了。
    DDNS 让你有一个永不变的名字，指向当前地址。

用法：
    # 1) 先看看现在检测到什么（不需要 token）
    python ddns.py --show

    # 2) 绑定 DuckDNS（去 duckdns.org 免费注册，拿 domain + token）
    python ddns.py --domain 你的名字 --token 你的token

    # 3) 常驻，每 5 分钟自动更新（地址变了就自动改）
    python ddns.py --domain 你的名字 --token 你的token --loop

DuckDNS 免费、无需备案、支持 IPv6（AAAA）和 IPv4（A）。
"""

import argparse
import json
import os
import re
import socket
import sys
import time
import urllib.parse
import urllib.request

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

TIMEOUT = 10


def http_get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'hljjs-ddns/1.0'})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return r.read().decode('utf-8', 'replace').strip()


def http_json(url):
    return json.loads(http_get(url))


def detect_outbound_ipv6():
    """对外看到的 IPv6（可能是代理出口，仅供参考）"""
    try:
        return http_json('https://api6.ipify.org?format=json').get('ip', '')
    except Exception:
        return ''


def detect_outbound_ipv4():
    try:
        return http_json('https://api.ipify.org?format=json').get('ip', '')
    except Exception:
        return ''


def detect_local_ipv6():
    """
    本机网卡上的全局 IPv6 —— 这个才是别人能连进来的地址。
    排除：回环、链路本地(fe80)、临时地址（会轮换）。
    """
    best = ''
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET6)
        for info in infos:
            addr = info[4][0].split('%')[0]
            if not addr or addr == '::1':
                continue
            if addr.lower().startswith('fe80'):
                continue
            best = addr
    except Exception:
        pass
    return best


def update_duckdns(domain, token, ipv6='', ipv4=''):
    params = {'domains': domain, 'token': token, 'verbose': 'true'}
    if ipv6:
        params['ipv6'] = ipv6
    if ipv4:
        params['ip'] = ipv4
    url = 'https://www.duckdns.org/update?' + urllib.parse.urlencode(params)
    try:
        out = http_get(url)
    except Exception as e:
        return False, '请求失败：%s' % e
    return out.startswith('OK'), out


def show(domain=''):
    v6 = detect_local_ipv6()
    out6 = detect_outbound_ipv6()
    out4 = detect_outbound_ipv4()

    print('本机全局 IPv6 ：', v6 or '（没有）')
    print('出口看到的 IPv6：', out6 or '（没有）')
    print('出口看到的 IPv4：', out4 or '（没有）')
    if v6 and out6 and v6.lower() != out6.lower():
        print()
        print('  ! 两个 IPv6 不一样 —— 说明流量走了别的出口（VPN / 代理 / 多网卡）。')
        print('    对外发布应该用「本机全局 IPv6」，但需要确认它真的能从外网连进来。')
    if domain:
        print()
        print('域名：', '%s.duckdns.org' % domain)
        print('访问： http://%s.duckdns.org:8642/' % domain)



def dns_lookup(name, rtype='AAAA'):
    """用 Google DNS 查真实的解析结果（验证域名真的指过去了）"""
    try:
        data = http_json('https://dns.google/resolve?name=%s&type=%s' % (
            urllib.parse.quote(name), rtype))
        return [a.get('data', '') for a in data.get('Answer', []) if a.get('type') == (28 if rtype == 'AAAA' else 1)]
    except Exception as e:
        return []


def check(domain, port=8642):
    host = '%s.duckdns.org' % domain
    print('检查 %s 的解析结果…' % host)
    aaaa = dns_lookup(host, 'AAAA')
    a = dns_lookup(host, 'A')
    local6 = detect_local_ipv6()

    print('  公网 DNS 的 AAAA：', ', '.join(aaaa) if aaaa else '（没有）')
    print('  公网 DNS 的 A 　：', ', '.join(a) if a else '（没有）')
    print('  本机网卡 IPv6 　：', local6 or '（没有）')

    ok = False
    if aaaa:
        if local6 and local6.lower() in [x.lower() for x in aaaa]:
            print('  ✓ 解析正确，指向本机')
            ok = True
        else:
            print('  ! 解析到了别的地址 —— 可能还没生效，或本机 IPv6 变了（再跑一次更新）')
    else:
        print('  ! 没有 AAAA 记录 —— IPv6 没发布成功')

    print()
    print('  注意：这里只验证「域名指对了」，不等于「外网真的能连进来」。')
    print('  真正的验证必须用另一台设备（手机开流量）打开：')
    print('      http://%s:%d/' % (host, port))
    print('  能打开 = 防火墙和路由器都放行了。打不开就看这两处。')
    return ok


def main():
    ap = argparse.ArgumentParser(description='把当前公网地址定格成固定域名')
    ap.add_argument('--domain', default='', help='DuckDNS 子域名（不含 .duckdns.org）')
    ap.add_argument('--token', default='', help='DuckDNS token')
    ap.add_argument('--ipv6', default='', help='手动指定要发布的 IPv6')
    ap.add_argument('--ipv4', default='', help='手动指定要发布的 IPv4')
    ap.add_argument('--show', action='store_true', help='只显示检测结果')
    ap.add_argument('--check', action='store_true', help='验证域名解析是否指向本机')
    ap.add_argument('--loop', action='store_true', help='常驻，定时更新')
    ap.add_argument('--interval', type=int, default=300, help='更新间隔秒数（默认 300）')
    args = ap.parse_args()

    # 没传参数时，尝试读同目录的 ddns.json
    if not args.domain:
        conf = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ddns.json')
        if os.path.exists(conf):
            try:
                cfg = json.load(open(conf, encoding='utf-8-sig'))
                args.domain = cfg.get('domain', '') or args.domain
                args.token = cfg.get('token', '') or args.token
            except Exception as e:
                print('读取 ddns.json 失败：', e)

    if args.show or not args.domain:
        show(args.domain)
        return 0

    if args.check:
        return 0 if check(args.domain) else 1

    if not args.token:
        print('')
        print('缺少 --token，去 https://www.duckdns.org 登录后复制。')
        return 1

    def once():
        v6 = args.ipv6 or detect_local_ipv6()
        v4 = args.ipv4 or ''
        ok, msg = update_duckdns(args.domain, args.token, v6, v4)
        stamp = time.strftime('%H:%M:%S')
        if ok:
            print('[%s] 已更新 → %s.duckdns.org = %s' % (stamp, args.domain, v6 or '(未发布 IPv6)'))
            return True
        print('[%s] 更新失败：%s' % (stamp, msg))
        return False

    if not args.loop:
        ok = once()
        if ok:
            print()
            print('别人现在可以用这个地址访问你：')
            print('    http://%s.duckdns.org:8642/' % args.domain)
        return 0 if ok else 1

    if args.check:
        check(args.domain)
        return 0

    print('常驻模式，每 %d 秒检查一次（Ctrl+C 退出）' % args.interval)
    last = ''
    while True:
        v6 = args.ipv6 or detect_local_ipv6()
        if v6 != last:
            once()
            last = v6
        else:
            print('[%s] 地址未变，跳过' % time.strftime('%H:%M:%S'))
        try:
            time.sleep(args.interval)
        except KeyboardInterrupt:
            print('\n已停止')
            return 0


if __name__ == '__main__':
    sys.exit(main())
