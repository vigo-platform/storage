package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.*;

class JwtCryptoTest {
    static RSAKey key;NimbusJwtDecoder decoder;
    @BeforeAll static void key()throws Exception{key=new RSAKeyGenerator(2048).keyID("fixture").generate();}
    @BeforeEach void decoder()throws Exception{
        decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();decoder.setJwtValidator(SecurityConfiguration.validators("https://issuer.test","storage"));
    }
    JWTClaimsSet.Builder claims(){return new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer("https://issuer.test").audience("storage").claim("role","COACH").issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(Instant.now().plusSeconds(120)));}
    String sign(JWTClaimsSet claims,RSAKey signing,JWSAlgorithm algorithm)throws Exception{
        var jwt=new SignedJWT(new JWSHeader.Builder(algorithm).keyID("fixture").build(),claims);jwt.sign(new RSASSASigner(signing));return jwt.serialize();
    }
    void rejected(JWTClaimsSet claims)throws Exception{String token=sign(claims,key,JWSAlgorithm.RS256);assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);}
    @Test void validRs256Accepted()throws Exception{assertThat(decoder.decode(sign(claims().build(),key,JWSAlgorithm.RS256)).getClaimAsString("role")).isEqualTo("COACH");}
    @Test void wrongSignatureRejected()throws Exception{
        var foreign=new RSAKeyGenerator(2048).generate();String token=sign(claims().build(),foreign,JWSAlgorithm.RS256);assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void otherRsaAlgorithmRejected()throws Exception{String token=sign(claims().build(),key,JWSAlgorithm.RS512);assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);}
    @Test void unsignedTokenRejected(){String token=new PlainJWT(claims().build()).serialize();assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);}
    @Test void expiredRejected()throws Exception{rejected(claims().expirationTime(Date.from(Instant.now().minusSeconds(1))).build());}
    @Test void futureNotBeforeRejected()throws Exception{rejected(claims().notBeforeTime(Date.from(Instant.now().plusSeconds(60))).build());}
    @Test void missingExpirationRejected()throws Exception{rejected(claims().expirationTime(null).build());}
    @Test void issuerAndAudienceRequired()throws Exception{rejected(claims().issuer("https://other.test").build());rejected(claims().audience("another-service").build());}
    @Test void arrayRoleRejected()throws Exception{rejected(claims().claim("role",List.of("COACH")).build());}
    @ParameterizedTest @ValueSource(strings={"ADMINISTRATOR","coach","","SUSPENDED"})
    void unknownRolesRejected(String role)throws Exception{rejected(claims().claim("role",role).build());}
    @Test void missingRoleRejected()throws Exception{rejected(claims().claim("role",null).build());}
    @Test void nonCanonicalSubjectRejected()throws Exception{rejected(claims().subject("1-1-1-1-1").build());rejected(claims().subject("not-a-uuid").build());}
    @Test void emptyRequiredConfigurationFailsClosed(){assertThatThrownBy(()->new SecurityConfiguration().jwtDecoder("http://localhost/jwks","","storage")).isInstanceOf(IllegalStateException.class);}
}
