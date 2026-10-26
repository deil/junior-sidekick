import { Component, inject } from '@angular/core';
import { AgentProfileStore } from '../agent-profile';

@Component({
  selector: 'sk-agent-intro',
  templateUrl: './agent-intro.html',
  styleUrl: './agent-intro.css',
})
export class AgentIntro {
  protected readonly profile = inject(AgentProfileStore);
}
