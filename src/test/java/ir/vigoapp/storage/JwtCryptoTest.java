package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
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
        decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).validateType(false).build();
        decoder.setJwtValidator(SecurityConfiguration.validators(AuthTokenFixture.ISSUER, AuthTokenFixture.AUDIENCE));
    }
    JWTClaimsSet.Builder claims(){return AuthTokenFixture.claims("42","COACH");}
    String sign(JWTClaimsSet claims,RSAKey signing,JWSAlgorithm algorithm)throws Exception{
        return AuthTokenFixture.sign(claims,signing,algorithm,"at+jwt");
    }
    void rejected(JWTClaimsSet claims)throws Exception{String token=sign(claims,key,JWSAlgorithm.RS256);assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);}
    @Test void authAccessTokenAccepted()throws Exception{
        var jwt=decoder.decode(sign(AuthTokenFixture.claims("42","COACH","ATHLETE").build(),key,JWSAlgorithm.RS256));
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("COACH","ATHLETE");
    }
    @Test void wrongSignatureRejected()throws Exception{
        var foreign=new RSAKeyGenerator(2048).generate();String token=sign(claims().build(),foreign,JWSAlgorithm.RS256);assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    @Test void otherRsaAlgorithmRejected()throws Exception{String token=sign(claims().build(),key,JWSAlgorithm.RS512);assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);}
    @Test void unsignedTokenRejected(){String token=new PlainJWT(claims().build()).serialize();assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);}
    @Test void expiredRejected()throws Exception{rejected(claims().expirationTime(Date.from(Instant.now().minusSeconds(1))).build());}
    @Test void futureNotBeforeRejected()throws Exception{rejected(claims().notBeforeTime(Date.from(Instant.now().plusSeconds(60))).build());}
    @Test void missingExpirationRejected()throws Exception{rejected(claims().expirationTime(null).build());}
    @Test void issuerAndAudienceRequired()throws Exception{rejected(claims().issuer("https://other.test").build());rejected(claims().audience("another-service").build());}
    @Test void scalarAndNonStringRolesRejected()throws Exception{
        rejected(claims().claim("roles","COACH").build());
        rejected(claims().claim("roles",List.of("COACH",42)).build());
    }
    @ParameterizedTest @ValueSource(strings={"ADMINISTRATOR","coach","","SUSPENDED"})
    void unknownRolesRejected(String role)throws Exception{rejected(claims().claim("roles",List.of(role)).build());}
    @Test void missingOrEmptyRolesRejected()throws Exception{
        rejected(claims().claim("roles",null).claim("role","COACH").build());
        rejected(claims().claim("roles",List.of()).build());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","+1","01"," 1","9223372036854775808","4e2","c3f17438-4c43-45f1-a1b1-8babdb6f68f2"})
    void nonCanonicalSubjectRejected(String subject)throws Exception{rejected(claims().subject(subject).build());}
    @Test void missingOrWrongTypeAndTokenUseRejected()throws Exception{
        for(String type:Arrays.asList(null,"JWT","AT+JWT")) {
            String token=AuthTokenFixture.sign(claims().build(),key,JWSAlgorithm.RS256,type);
            assertThatThrownBy(()->decoder.decode(token)).isInstanceOf(JwtException.class);
        }
        rejected(claims().claim("token_use",null).build());
        rejected(claims().claim("token_use","refresh").build());
    }
    @Test void emptyRequiredConfigurationFailsClosed(){assertThatThrownBy(()->new SecurityConfiguration().jwtDecoder("http://localhost/jwks","","storage")).isInstanceOf(IllegalStateException.class);}
}
