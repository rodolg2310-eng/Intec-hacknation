package com.apprentice.auth;
import com.apprentice.common.ApiException;
import com.apprentice.tenant.TenantFilter;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InvitationExpiryTest {
  @Test void expiredRevokedAndConsumedTokensAreRejected(){
    InvitationRepository repo=mock(InvitationRepository.class);TeamService team=new TeamService(mock(AuthService.class),mock(AppAccountRepository.class),repo);
    Invitation invite=new Invitation();invite.email="invited@example.com";invite.role=Role.LEARNER;invite.expiresAt=Instant.now().minusSeconds(1);
    when(repo.findByTokenHash(TenantFilter.sha256("token"))).thenReturn(Optional.of(invite));assertThrows(ApiException.class,()->team.preview("token"));
    invite.expiresAt=Instant.now().plusSeconds(3600);invite.revoked=true;assertThrows(ApiException.class,()->team.preview("token"));
    invite.revoked=false;invite.consumedAt=Instant.now();assertThrows(ApiException.class,()->team.preview("token"));
    invite.consumedAt=null;assertEquals("invited@example.com",team.preview("token").get("email"));
  }
}
