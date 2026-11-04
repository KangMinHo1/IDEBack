package com.myide.backend.service.apitest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

public class SsrfGuard {

    // 시연/개발용 설정
    // true면 localhost, 127.0.0.1, 192.168.x.x, 10.x.x.x, 172.16~31.x.x 허용
    // API 테스터가 로컬·LAN 시연에서 IDE 로 실행한 서버(http://localhost:{포트}, http://{LAN IP}:{포트})와
    // 이 백엔드(Local 환경 http://localhost:8080)를 부를 때 이 허용에 기댄다.
    private static final boolean ALLOW_PRIVATE_NETWORK_FOR_DEMO = true;

    // AWS 의 IPv6 메타데이터 주소(fd00:ec2::254). 고유 로컬 주소라 isSiteLocalAddress 에 걸리지 않아 따로 본다.
    private static final byte[] AWS_IPV6_METADATA_PREFIX = {(byte) 0xfd, 0x00, 0x0e, (byte) 0xc2};

    public static void validateTargetUrl(String url) {
        try {
            URI uri = URI.create(url);

            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme == null || host == null || host.isBlank()) {
                throw new IllegalArgumentException("invalid url");
            }

            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("only http/https allowed");
            }

            resolveAllowed(host);

        } catch (Exception e) {
            throw new IllegalArgumentException("invalid target url: " + e.getMessage(), e);
        }
    }

    /**
     * 호스트가 가리키는 주소를 모두 확인하고 돌려준다. 하나라도 막힌 주소면 거부한다.
     * (예전에는 첫 주소만 봤다. 여러 주소 중 다른 것으로 붙으면 검사를 피할 수 있었다.)
     *
     * ProxyService 는 실제로 연결할 때도 이것으로 주소를 받는다(DnsResolver). 검사한 주소와 연결하는 주소가
     * 같아지므로, 검사 뒤 DNS 응답을 바꿔 내부 주소로 붙게 하는 수법(DNS rebinding)이 통하지 않는다.
     * 리다이렉트로 옮겨 간 곳도 새로 연결하므로 여기를 다시 지난다.
     */
    public static InetAddress[] resolveAllowed(String host) throws UnknownHostException {
        // URI 의 IPv6 호스트는 [::1] 처럼 대괄호가 붙어 온다.
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses = InetAddress.getAllByName(bare);

        for (InetAddress address : addresses) {
            checkAddress(address);
        }

        return addresses;
    }

    static void checkAddress(InetAddress address) {
        // 0.0.0.0 같은 주소는 허용하지 않음
        if (address.isAnyLocalAddress()) {
            throw new IllegalArgumentException("any local address not allowed");
        }

        // 169.254.x.x, fe80::/10 — 클라우드 메타데이터(EC2 169.254.169.254)가 여기 있다. 시연 설정과 상관없이 막는다.
        if (address.isLinkLocalAddress() || isAwsIpv6Metadata(address)) {
            throw new IllegalArgumentException("link-local/metadata address not allowed");
        }

        if (address.isMulticastAddress()) {
            throw new IllegalArgumentException("multicast address not allowed");
        }

        // localhost, 127.x.x.x 허용
        if (address.isLoopbackAddress()) {
            if (ALLOW_PRIVATE_NETWORK_FOR_DEMO) {
                return;
            }

            throw new IllegalArgumentException("private/internal address not allowed");
        }

        // 192.168.x.x, 10.x.x.x, 172.16~31.x.x 허용
        if (address.isSiteLocalAddress()) {
            if (ALLOW_PRIVATE_NETWORK_FOR_DEMO) {
                return;
            }

            throw new IllegalArgumentException("private/internal address not allowed");
        }
    }

    private static boolean isAwsIpv6Metadata(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }

        byte[] bytes = address.getAddress();
        for (int i = 0; i < AWS_IPV6_METADATA_PREFIX.length; i++) {
            if (bytes[i] != AWS_IPV6_METADATA_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }
}
