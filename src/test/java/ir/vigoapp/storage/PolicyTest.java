package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyTest {
    UUID coach=UUID.randomUUID(),athlete=UUID.randomUUID(),other=UUID.randomUUID();
    Policy.Actor actor(UUID id,Policy.Role role){return new Policy.Actor(id,role);}
    @Test void onlyOwnerCoachCanUpload(){
        Policy.upload(actor(coach,Policy.Role.COACH),coach,athlete);
        for(Policy.Role role:new Policy.Role[]{Policy.Role.ADMIN,Policy.Role.ATHLETE})assertThatThrownBy(()->Policy.upload(actor(coach,role),coach,athlete)).isInstanceOf(Api.Fault.class);
        assertThatThrownBy(()->Policy.upload(actor(other,Policy.Role.COACH),coach,athlete)).isInstanceOf(Api.Fault.class);
    }
    @Test void selfTargetNeverAllowed(){assertThatThrownBy(()->Policy.upload(actor(coach,Policy.Role.COACH),coach,coach)).isInstanceOf(Api.Fault.class);}
    @Test void editsOnlyOwnerCoachOrAdmin(){
        Policy.edit(actor(coach,Policy.Role.COACH),coach);Policy.edit(actor(other,Policy.Role.ADMIN),coach);
        assertThatThrownBy(()->Policy.edit(actor(athlete,Policy.Role.ATHLETE),coach)).isInstanceOf(Api.Fault.class);
        assertThatThrownBy(()->Policy.edit(actor(other,Policy.Role.COACH),coach)).isInstanceOf(Api.Fault.class);
    }
    @Test void forgedScopeFiltersRejectedButCrossPartyFiltersAllowed(){
        assertThatThrownBy(()->Policy.filter(actor(coach,Policy.Role.COACH),other,null)).isInstanceOf(Api.Fault.class);
        assertThatThrownBy(()->Policy.filter(actor(athlete,Policy.Role.ATHLETE),null,other)).isInstanceOf(Api.Fault.class);
        Policy.filter(actor(coach,Policy.Role.COACH),coach,other);Policy.filter(actor(athlete,Policy.Role.ATHLETE),other,athlete);Policy.filter(actor(other,Policy.Role.ADMIN),coach,athlete);
    }
}
